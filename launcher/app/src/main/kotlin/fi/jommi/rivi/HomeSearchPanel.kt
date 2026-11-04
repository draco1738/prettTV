package fi.jommi.rivi

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal data class HomeSearchResult(val key:String,val label:String,val group:String,val open:()->Unit)
internal class HomeSearchState(var query:String="",var selectedKey:String?=null)

/** Query and results share one surface. Opening a result keeps the search behind it. */
internal class HomeSearchPanel(activity:Activity,private val state:HomeSearchState,
    private val find:(String)->List<HomeSearchResult>):Dialog(activity) {
    private val scale=activity.resources.displayMetrics.widthPixels/1920f
    private fun px(n:Int)=(n*scale+.5f).toInt().coerceAtLeast(1)
    private val input=EditText(context).apply {
        tag="home-search-query";id=View.generateViewId();hint="App, show or movie";isSingleLine=true
        setTextColor(AppActionStyle.IVORY);setHintTextColor(AppActionStyle.MUTED);AppActionStyle.textSize(this,28,scale)
        imeOptions=EditorInfo.IME_ACTION_SEARCH;setText(state.query)
        filters=arrayOf(android.text.InputFilter.LengthFilter(200))
    }
    private val status=TextView(context).apply { tag="home-search-status";setTextColor(AppActionStyle.MUTED);AppActionStyle.textSize(this,24,scale) }
    private val results=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
    private val search=button("Search") { submit() }
    private val clear=button("Clear") { input.setText("");state.query="";state.selectedKey=null;submit();input.requestFocus() }
    private val back=button("Back") { dismiss() }
    private var limit=60
    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val page=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(px(80),px(48),px(80),px(48));setBackgroundColor(PrettTvColors.BACKGROUND) }
        page.addView(TextView(context).apply { setText(R.string.home_search_title);setTextColor(AppActionStyle.IVORY);AppActionStyle.textSize(this,42,scale) })
        page.addView(TextView(context).apply {
            setText(R.string.home_search_scope)
            setTextColor(AppActionStyle.MUTED);AppActionStyle.textSize(this,23,scale)
        },LinearLayout.LayoutParams(-1,-2).apply { topMargin=px(8);bottomMargin=px(24) })
        val bar=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL }
        bar.addView(input,LinearLayout.LayoutParams(0,px(70),1f).apply { rightMargin=px(24) })
        listOf(search,clear,back).forEach { bar.addView(it,LinearLayout.LayoutParams(px(180),px(70)).apply { leftMargin=px(10) }) }
        page.addView(bar)
        page.addView(status,LinearLayout.LayoutParams(-1,-2).apply { topMargin=px(22);bottomMargin=px(12) })
        page.addView(ScrollView(context).apply { tag="home-search-results";addView(results) },LinearLayout.LayoutParams(-1,0,1f))
        setContentView(page)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(PrettTvColors.BACKGROUND));setLayout(-1,-1)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        input.setOnEditorActionListener { _,action,event ->
            if(action==EditorInfo.IME_ACTION_SEARCH || event?.let { it.keyCode==android.view.KeyEvent.KEYCODE_ENTER && it.action==android.view.KeyEvent.ACTION_UP }==true) { submit();true } else false
        }
        renderResults(false)
        setOnShowListener {
            val selected=results.findViewWithTag<View>("search-${state.selectedKey}")
            if(selected!=null)selected.requestFocus() else input.requestFocus()
            input.setSelection(input.text.length)
        }
    }
    private fun button(label:String,action:()->Unit)=Button(context).apply {
        id=View.generateViewId();text=label;AppActionStyle.button(this,scale);setOnClickListener { action() }
    }
    private fun submit() {
        state.query=input.text.toString().trim();state.selectedKey=null;limit=60
        (context.getSystemService(Activity.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(input.windowToken,0)
        renderResults(false)
    }
    private fun renderResults(restore:Boolean) {
        results.removeAllViews()
        val matches=if(state.query.isBlank())emptyList() else find(state.query)
        status.text=when { state.query.isBlank()->"Enter a name to begin.";matches.isEmpty()->"No matches in your apps or loaded Home titles. Try a shorter name, or use voice search.";matches.size==1->"1 result";else->"${matches.size} results" }
        val buttons=ArrayList<Button>();var group=""
        matches.take(limit).forEach { item ->
            if(group!=item.group) {
                group=item.group
                results.addView(TextView(context).apply { text=group;setTextColor(AppActionStyle.MUTED);AppActionStyle.textSize(this,23,scale) },LinearLayout.LayoutParams(-1,-2).apply { topMargin=px(14);bottomMargin=px(6) })
            }
            val b=button(item.label) { state.selectedKey=item.key;item.open() }.apply {
                tag="search-${item.key}";gravity=android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                setOnFocusChangeListener { _,focused -> if(focused)state.selectedKey=item.key }
            }
            buttons+=b;results.addView(b,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=px(4) })
        }
        if(matches.size>limit) {
            val b=button("More results") {
                val firstNew=limit;limit+=60;renderResults(false)
                matches.getOrNull(firstNew)?.let { results.findViewWithTag<View>("search-${it.key}")?.requestFocus() }
            };buttons+=b;results.addView(b)
        }
        val top=listOf(input,search,clear,back)
        top.forEachIndexed { i,v ->
            v.nextFocusLeftId=top[(i-1).coerceAtLeast(0)].id;v.nextFocusRightId=top[(i+1).coerceAtMost(top.lastIndex)].id
            v.nextFocusUpId=v.id;v.nextFocusDownId=buttons.firstOrNull()?.id ?: v.id
        }
        buttons.forEachIndexed { i,b ->
            b.nextFocusUpId=buttons.getOrNull(i-1)?.id ?: input.id;b.nextFocusDownId=buttons.getOrNull(i+1)?.id ?: b.id
            b.nextFocusLeftId=b.id;b.nextFocusRightId=b.id
        }
        if(restore)buttons.firstOrNull { it.tag=="search-${state.selectedKey}" }?.requestFocus()
    }
    override fun dismiss() { state.query=input.text.toString().take(200);super.dismiss() }
}
