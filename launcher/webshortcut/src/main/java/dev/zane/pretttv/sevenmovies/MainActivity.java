package dev.zane.pretttv.sevenmovies;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** A launcher entry that delegates browsing to TV Bro without owning a browser session. */
public final class MainActivity extends Activity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse("https://7movies.ac/discover"))
                .setComponent(new ComponentName(
                        "com.phlox.tvwebbrowser",
                        "com.phlox.tvwebbrowser.activity.main.MainActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("com.phlox.tvwebbrowser.EXTRA_OPEN_IN_SAME_TAB", true);
        try {
            startActivity(browser);
            finish();
        } catch (ActivityNotFoundException exception) {
            showError(R.string.browser_unavailable);
        } catch (SecurityException exception) {
            showError(R.string.browser_blocked);
        }
    }

    private void showError(int message) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.app_name)
                .setMessage(message)
                .setPositiveButton(R.string.close, (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish())
                .show();
    }
}
