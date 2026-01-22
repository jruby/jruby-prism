package org.jruby.prism.parser;

import org.jcodings.Encoding;
import org.jcodings.specific.ISO8859_1Encoding;
import org.jruby.*;
import org.jruby.parser.Parser;
import org.jruby.parser.ParserType;
import org.jruby.parser.StaticScope;
import org.jruby.runtime.DynamicScope;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ByteList;
import org.jruby.util.CommonByteLists;
import org.prism.Nodes.*;
import org.prism.ParsingOptions;
import org.prism.Prism;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.jruby.api.Convert.asSymbol;
import static org.jruby.lexer.LexingCommon.DOLLAR_UNDERSCORE;
import static org.jruby.parser.ParserType.EVAL;

public class ParserPrismWasm extends ParserPrismBase {
    public ParserPrismWasm(Ruby runtime) {
        super(runtime);
    }

    protected byte[] parse(byte[] source, int sourceLength, byte[] metadata) {
        try (Prism prism = new Prism()) {
            return prism.serialize(metadata, source, sourceLength);
        }
    }
}
