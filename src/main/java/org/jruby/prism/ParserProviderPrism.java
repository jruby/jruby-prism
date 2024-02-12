package org.jruby.prism;

import org.jruby.Ruby;
import org.jruby.ir.builder.IRBuilderFactory;
import org.jruby.parser.Parser;
import org.jruby.parser.ParserProvider;
import org.jruby.prism.parser.ParserPrism;
import org.jruby.prism.parser.ParserBindingPrism;
import org.jruby.prism.builder.IRBuilderFactoryPrism;

import jnr.ffi.LibraryLoader;

public class ParserProviderPrism implements ParserProvider {
    private static ParserBindingPrism prismLibrary;

    public void initialize(String path) {
        if (prismLibrary != null) {
            System.out.println("Prism already initialized");
            return;
        }
        prismLibrary = LibraryLoader.create(ParserBindingPrism.class).load(path);
        // We do something extra here as a side-effect which is how we get an UnsatisfiedLinkError
        // If the library didn't in fact find the .so or has other loading problems.
        prismLibrary.toString();
    }

    public Parser getParser(Ruby runtime) {
        return new ParserPrism(runtime, prismLibrary);
    }

    public IRBuilderFactory getBuilderFactory() {
        return new IRBuilderFactoryPrism();
    }
}
