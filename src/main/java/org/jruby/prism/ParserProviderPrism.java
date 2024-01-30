package org.jruby.prism;

import org.jruby.Ruby;
import org.jruby.ir.builder.IRBuilderFactory;
import org.jruby.parser.Parser;
import org.jruby.parser.ParserProvider;
import org.jruby.prism.parser.ParserPrism;
import org.jruby.prism.builder.IRBuilderFactoryPrism;

public class ParserProviderPrism implements ParserProvider {
    public Parser getParser(Ruby runtime) {
        return new ParserPrism(runtime);
    }

    public IRBuilderFactory getBuilderFactory() {
        return new IRBuilderFactoryPrism();
    }
}
