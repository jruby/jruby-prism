package org.jruby.prism.parser;

import org.jcodings.Encoding;
import org.jruby.Ruby;
import org.jruby.util.ByteList;
import org.ruby_lang.prism.Loader;
import org.ruby_lang.prism.Nodes;
import org.ruby_lang.prism.ParseResult;

/**
 * Extends Loader to override some things which are not generated directly
 * for JRuby.
 */
public class LoaderPrism extends Loader {
    private Ruby runtime;

    private Encoding encoding = null;

    LoaderPrism(Ruby runtime, byte[] serialized, byte[] source) {
        super(serialized, source);

        this.runtime = runtime;
    }

    public ParseResult load() {
        ParseResult result = super.load();

        resolveEncoding();

        return result;
    }

    public org.jruby.RubySymbol bytesToName(byte[] bytes) {
        resolveEncoding();
        return runtime.newSymbol(new ByteList(bytes, encoding));
    }

    private void resolveEncoding() {
        if (encoding == null) {
            encoding = runtime.getEncodingService().findEncodingOrAliasEntry(encodingName.getBytes()).getEncoding();
        }
    }

    public Encoding getEncoding() {
        return encoding;
    }
}
