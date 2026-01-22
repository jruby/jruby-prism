package org.jruby.prism.parser;

import org.jcodings.Encoding;
import org.jcodings.specific.ISO8859_1Encoding;
import org.jruby.ParseResult;
import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyIO;
import org.jruby.RubyInstanceConfig;
import org.jruby.RubySymbol;
import org.jruby.ext.coverage.CoverageData;
import org.jruby.management.ParserStats;
import org.jruby.parser.Parser;
import org.jruby.parser.ParserManager;
import org.jruby.parser.ParserType;
import org.jruby.parser.StaticScope;
import org.jruby.runtime.DynamicScope;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.runtime.load.LoadServiceResourceInputStream;
import org.jruby.util.ByteList;
import org.jruby.util.CommonByteLists;
import org.jruby.util.io.ChannelHelper;
import org.prism.Nodes;
import org.prism.Nodes.*;
import org.prism.ParsingOptions;
import org.prism.Prism;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.jruby.api.Convert.asSymbol;
import static org.jruby.lexer.LexingCommon.DOLLAR_UNDERSCORE;
import static org.jruby.parser.ParserType.EVAL;
import static org.jruby.parser.ParserType.MAIN;

public class ParserPrismNative extends ParserPrismBase {
    private final ParserBindingPrism prismLibrary;

    public ParserPrismNative(Ruby runtime, ParserBindingPrism prismLibrary) {
        super(runtime);
        this.prismLibrary = prismLibrary;
    }

    protected byte[] parse(byte[] source, int sourceLength, byte[] metadata) {
        long time = 0;
        if (parserTiming) time = System.nanoTime();

        ParserBindingPrism.Buffer buffer = new ParserBindingPrism.Buffer(jnr.ffi.Runtime.getRuntime(prismLibrary));
        prismLibrary.pm_buffer_init(buffer);
        prismLibrary.pm_serialize_parse(buffer, source, sourceLength, metadata);
        if (parserTiming) {
            ParserStats stats = runtime.getParserManager().getParserStats();

            stats.addPrismTimeCParseSerialize(System.nanoTime() - time);
        }

        int length = buffer.length.intValue();
        byte[] src = new byte[length];
        buffer.value.get().get(0, src, 0, length);

        return src;
    }
}
