package android.util;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;

/**
 * Host-only Android API adapter backed by pinned Gson 2.10.1, not an Android runtime.
 * Production WeatherJson retains all schema, token-type, duplicate and end checks.
 * This source is outside every Android source set and must never ship in an APK.
 */
public final class JsonReader implements Closeable {
    private final com.google.gson.stream.JsonReader delegate;
    public JsonReader(Reader input) { delegate = new com.google.gson.stream.JsonReader(input); }
    public void setLenient(boolean value) { delegate.setLenient(value); }
    public boolean isLenient() { return delegate.isLenient(); }
    public void beginObject() throws IOException { delegate.beginObject(); }
    public void endObject() throws IOException { delegate.endObject(); }
    public boolean hasNext() throws IOException { return delegate.hasNext(); }
    public String nextName() throws IOException { return delegate.nextName(); }
    public JsonToken peek() throws IOException { return JsonToken.valueOf(delegate.peek().name()); }
    public String nextString() throws IOException { return delegate.nextString(); }
    public double nextDouble() throws IOException { return delegate.nextDouble(); }
    @Override public void close() throws IOException { delegate.close(); }
}
