package org.jruby.prism.parser;

import org.jruby.Ruby;
import org.prism.Prism;
import org.prism.PrismWASM;

public class ParserPrismWasm extends ParserPrismBase {
    private static final Prism prism = new PrismWASM();

    public ParserPrismWasm(Ruby runtime) {
        super(runtime);
    }

    protected byte[] parse(byte[] source, int sourceLength, byte[] metadata) {
        return prism.serialize(metadata, source, sourceLength);
    }
}
