package org.jruby.prism.builder;

import org.jcodings.Encoding;
import org.jcodings.specific.ASCIIEncoding;
import org.jcodings.specific.EUCJPEncoding;
import org.jcodings.specific.USASCIIEncoding;
import org.jcodings.specific.UTF8Encoding;
import org.jcodings.specific.Windows_31JEncoding;
import org.jruby.ParseResult;
import org.jruby.Ruby;
import org.jruby.RubyBignum;
import org.jruby.RubyFloat;
import org.jruby.RubyKernel;
import org.jruby.RubyNumeric;
import org.jruby.RubyString;
import org.jruby.RubySymbol;
import org.jruby.compiler.NotCompilableException;
import org.jruby.ir.IRClosure;
import org.jruby.ir.IRManager;
import org.jruby.ir.IRMethod;
import org.jruby.ir.IRModuleBody;
import org.jruby.ir.IRScope;
import org.jruby.ir.IRScriptBody;
import org.jruby.ir.Tuple;
import org.jruby.ir.builder.IRBuilder;
import org.jruby.ir.builder.LazyMethodDefinition;
import org.jruby.ir.instructions.*;
import org.jruby.ir.instructions.defined.GetErrorInfoInstr;
import org.jruby.ir.instructions.defined.RestoreErrorInfoInstr;
import org.jruby.ir.operands.Array;
import org.jruby.ir.operands.Bignum;
import org.jruby.ir.operands.Complex;
import org.jruby.ir.operands.CurrentScope;
import org.jruby.ir.operands.Fixnum;
import org.jruby.ir.operands.Float;
import org.jruby.ir.operands.FrozenString;
import org.jruby.ir.operands.Hash;
import org.jruby.ir.operands.ImmutableLiteral;
import org.jruby.ir.operands.Label;
import org.jruby.ir.operands.LocalVariable;
import org.jruby.ir.operands.MutableString;
import org.jruby.ir.operands.NullBlock;
import org.jruby.ir.operands.Operand;
import org.jruby.ir.operands.Rational;
import org.jruby.ir.operands.Regexp;
import org.jruby.ir.operands.Splat;
import org.jruby.ir.operands.Symbol;
import org.jruby.ir.operands.SymbolProc;
import org.jruby.ir.operands.UndefinedValue;
import org.jruby.ir.operands.Variable;
import org.jruby.parser.StaticScope;
import org.jruby.parser.StaticScopeFactory;
import org.jruby.runtime.ArgumentDescriptor;
import org.jruby.runtime.ArgumentType;
import org.jruby.runtime.CallType;
import org.jruby.runtime.Signature;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ByteList;
import org.jruby.util.DefinedMessage;
import org.jruby.util.KCode;
import org.jruby.util.KeyValuePair;
import org.jruby.util.RegexpOptions;
import org.jruby.util.StringSupport;
import org.jruby.util.cli.Options;
import org.prism.Nodes;
import org.prism.Nodes.*;
import org.jruby.prism.parser.ParseResultPrism;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.jruby.ir.instructions.RuntimeHelperCall.Methods.*;
import static org.jruby.runtime.CallType.VARIABLE;
import static org.jruby.runtime.ThreadContext.*;
import static org.jruby.util.CommonByteLists.*;
import static org.jruby.util.RubyStringBuilder.inspectIdentifierByteList;
import static org.jruby.util.RubyStringBuilder.str;
import static org.jruby.util.StringSupport.CR_UNKNOWN;

public class IRBuilderPrism extends IRBuilder<Node, DefNode, WhenNode, RescueNode, ConstantPathNode, HashPatternNode> {
    byte[] source;

    Nodes.Source nodeSource;

    StaticScope staticScope;

    public IRBuilderPrism(IRManager manager, IRScope scope, IRBuilder parent, IRBuilder variableBuilder, Encoding encoding) {
        super(manager, scope, parent, variableBuilder, encoding);

        if (parent != null) {
            source = ((IRBuilderPrism) parent).source;
            nodeSource = ((IRBuilderPrism) parent).nodeSource;
        }
        staticScope = scope.getStaticScope();
        staticScope.setFile(scope.getFile()); // staticScope and IRScope contain the same field.
    }

    // FIXME: Delete once we are no longer depedent on source code
    public void setSourceFrom(IRBuilderPrism other) {
        if (other.nodeSource == null) throw new RuntimeException("WETF");
        this.nodeSource = other.nodeSource;
        this.source = other.source;
    }

    protected void hackPostExeSource(IRBuilder builder) {
        setSourceFrom((IRBuilderPrism) builder);
    }

    // FIXME: Delete once we are no longer depedent on source code
    public void setSourceFrom(Nodes.Source nodeSource, byte[] source) {
        this.nodeSource = nodeSource;
        this.source = source;
    }

    @Override
    public Operand build(ParseResult result) {
        // FIXME: Missing support for executes once
        this.executesOnce = false;
        this.source = ((ParseResultPrism) result).getSource();
        this.nodeSource = ((ParseResultPrism) result).getSourceNode();

//        System.out.println("NAME: " + fileName);
//        System.out.println(((ParseResultPrism) result).getRoot());

        return build(((ProgramNode) result.getAST()).statements);
    }

    protected Operand build(Node node) {
        return build(null, node);
    }

    /*
     * @param result preferred result variable (this reduces temp vars pinning values).
     * @param node to be built
     */
    protected Operand build(Variable result, Node node) {
        if (node == null) return nil();

        if (node.hasNewLineFlag()) determineIfWeNeedLineNumber(getLine(node), true, false, node instanceof DefNode);

        if (node instanceof AliasGlobalVariableNode) {
            return buildAliasGlobalVariable((AliasGlobalVariableNode) node);
        } else if (node instanceof AliasMethodNode) {
            return buildAliasMethod((AliasMethodNode) node);
        } else if (node instanceof AndNode) {
            return buildAnd((AndNode) node);
        } else if (node instanceof ArrayNode) {
            return buildArray((ArrayNode) node);                   // MISSING: ArrayPatternNode
        } else if (node instanceof AssocSplatNode) {
            return buildAssocSplat(result, (AssocSplatNode) node);
        } else if (node instanceof BackReferenceReadNode) {
            return buildBackReferenceRead(result, (BackReferenceReadNode) node);
        } else if (node instanceof BeginNode) {
            return buildBegin((BeginNode) node);
        } else if (node instanceof BlockArgumentNode) {
            return buildBlockArgument((BlockArgumentNode) node);
        } else if (node instanceof BlockNode) {
            return buildBlock((BlockNode) node);
        // BlockParameterNode processed during call building.
        } else if (node instanceof BreakNode) {
            return buildBreak((BreakNode) node);
        } else if (node instanceof CallNode) {
            return buildCall(result, (CallNode) node, ((CallNode) node).name);
        } else if (node instanceof CallAndWriteNode) {
            return buildCallAndWrite((CallAndWriteNode) node);
        } else if (node instanceof CallOrWriteNode) {
            return buildCallOrWrite((CallOrWriteNode) node);
        } else if (node instanceof CallOperatorWriteNode) { // foo.bar += baz
            return buildCallOperatorWrite((CallOperatorWriteNode) node);
        } else if (node instanceof CaseNode) {
            return buildCase((CaseNode) node);
        } else if (node instanceof CaseMatchNode) {
            return buildCaseMatch((CaseMatchNode) node);
        } else if (node instanceof ClassNode) {
            return buildClass((ClassNode) node);
        } else if (node instanceof ClassVariableAndWriteNode) {
            return buildClassAndVariableWrite((ClassVariableAndWriteNode) node);
        } else if (node instanceof ClassVariableOperatorWriteNode) {
            return buildClassVariableOperatorWrite((ClassVariableOperatorWriteNode) node);
        } else if (node instanceof ClassVariableOrWriteNode) {
            return buildClassOrVariableWrite((ClassVariableOrWriteNode) node);
        } else if (node instanceof ClassVariableReadNode) {
            return buildClassVariableRead(result, (ClassVariableReadNode) node);
        } else if (node instanceof ClassVariableWriteNode) {
            return buildClassVariableWrite((ClassVariableWriteNode) node);
        } else if (node instanceof ConstantAndWriteNode) {
            return buildConstantAndWrite((ConstantAndWriteNode) node);
        } else if (node instanceof ConstantOperatorWriteNode) {
            return buildConstantOperatorWrite((ConstantOperatorWriteNode) node);
        } else if (node instanceof ConstantOrWriteNode) {
            return buildConstantOrWrite((ConstantOrWriteNode) node);
        } else if (node instanceof ConstantPathNode) {
            return buildConstantPath(result, (ConstantPathNode) node);
        } else if (node instanceof ConstantPathAndWriteNode) {
            return buildConstantPathAndWrite((ConstantPathAndWriteNode) node);
        } else if (node instanceof ConstantPathOperatorWriteNode) {
            return buildConstantPathOperatorWrite((ConstantPathOperatorWriteNode) node);
        } else if (node instanceof ConstantPathOrWriteNode) {
            return buildConstantPathOrWrite((ConstantPathOrWriteNode) node);
        } else if (node instanceof ConstantPathOrWriteNode) {
            return buildConstantOrWritePath((ConstantPathOrWriteNode) node);
        } else if (node instanceof ConstantPathWriteNode) {
            return buildConstantWritePath((ConstantPathWriteNode) node);
        // ConstantPathTargetNode processed in multiple assignment
        } else if (node instanceof ConstantReadNode) {
            return buildConstantRead((ConstantReadNode) node);
        } else if (node instanceof ConstantWriteNode) {
            return buildConstantWrite((ConstantWriteNode) node);
        } else if (node instanceof DefNode) {
            return buildDef((DefNode) node);
        } else if (node instanceof DefinedNode) {
            return buildDefined((DefinedNode) node);
        } else if (node instanceof ElseNode) {
            return buildElse((ElseNode) node);
        } else if (node instanceof EmbeddedVariableNode) {
            return build(((EmbeddedVariableNode) node).variable);
        // EmbeddedStatementsNode handle in interpolated processing
        // MISSING: EnsureNode ???? begin will process stuff (and possibly ensure but it is unclear)
        } else if (node instanceof FalseNode) {
            return fals();
        } else if (node instanceof FloatNode) {                    // MISSING: FindPatternNode
            return buildFloat((FloatNode) node);
        } else if (node instanceof FlipFlopNode) {
            return buildFlipFlop((FlipFlopNode) node);
        } else if (node instanceof ForNode) {
            return buildFor((ForNode) node);
        // ForwardingArgumentsNode, ForwardingParametersNode process by def and call sides respectively
        } else if (node instanceof ForwardingSuperNode) {
            return buildForwardingSuper(result, (ForwardingSuperNode) node);
        } else if (node instanceof GlobalVariableAndWriteNode) {
            return buildGlobalVariableAndWrite((GlobalVariableAndWriteNode) node);
        } else if (node instanceof GlobalVariableOperatorWriteNode) {
            return buildGlobalVariableOperatorWrite((GlobalVariableOperatorWriteNode) node);
        } else if (node instanceof GlobalVariableOrWriteNode) {
            return buildGlobalVariableOrWrite((GlobalVariableOrWriteNode) node);
        } else if (node instanceof GlobalVariableReadNode) {
            return buildGlobalVariableRead(result, (GlobalVariableReadNode) node);
        // GlobalVariableTargetNode processed by muliple assignment
        } else if (node instanceof GlobalVariableWriteNode) {
            return buildGlobalVariableWrite((GlobalVariableWriteNode) node);
        } else if (node instanceof HashNode) {
            return buildHash(((HashNode) node).elements, containsVariableAssignment(node));
        } else if (node instanceof IfNode) {
            return buildIf(result, (IfNode) node);
        } else if (node instanceof ImaginaryNode) {
            return buildImaginary((ImaginaryNode) node);
        } else if (node instanceof ImplicitNode) {
            // Making a huge assumption the implicit node is what we always want for execution?
            return build(((ImplicitNode) node).value);
        } else if (node instanceof IndexAndWriteNode) {
            return buildIndexAndWrite((IndexAndWriteNode) node);
        } else if (node instanceof IndexOrWriteNode) {
            return buildIndexOrWrite((IndexOrWriteNode) node);
        } else if (node instanceof IndexOperatorWriteNode) {
            return buildIndexOperatorWrite((IndexOperatorWriteNode) node);
        } else if (node instanceof InstanceVariableAndWriteNode) {
            return buildInstanceVariableAndWrite((InstanceVariableAndWriteNode) node);
        } else if (node instanceof InstanceVariableOperatorWriteNode) {
            return buildInstanceVariableOperatorWrite((InstanceVariableOperatorWriteNode) node);
        } else if (node instanceof InstanceVariableOrWriteNode) {
            return buildInstanceVariableOrWrite((InstanceVariableOrWriteNode) node);
        } else if (node instanceof InstanceVariableReadNode) {
            return buildInstanceVariableRead((InstanceVariableReadNode) node);
        // InstanceVariableTargetNode processed by multiple assignment
        } else if (node instanceof InstanceVariableWriteNode) {
            return buildInstanceVariableWrite((InstanceVariableWriteNode) node);
        } else if (node instanceof IntegerNode) {
            return buildInteger((IntegerNode) node);
        } else if (node instanceof InterpolatedMatchLastLineNode) {
            return buildInterpolatedMatchLastLine(result, (InterpolatedMatchLastLineNode) node);
        } else if (node instanceof InterpolatedRegularExpressionNode) {
            return buildInterpolatedRegularExpression(result, (InterpolatedRegularExpressionNode) node);
        } else if (node instanceof InterpolatedStringNode) {
            return buildInterpolatedString(result, (InterpolatedStringNode) node);
        } else if (node instanceof InterpolatedSymbolNode) {
            return buildInterpolatedSymbol(result, (InterpolatedSymbolNode) node);
        } else if (node instanceof InterpolatedXStringNode) {
            return buildInterpolatedXString(result, (InterpolatedXStringNode) node);
        } else if (node instanceof KeywordHashNode) {
            return buildKeywordHash((KeywordHashNode) node, new int[1]); // FIXME: we don't care about flags but this is odd (seems to only be for array syntax with kwrest?).
        // KeywordParameterNode, KeywordRestParameterNode processed by call
        } else if (node instanceof LambdaNode) {
            return buildLambda((LambdaNode) node);
        } else if (node instanceof LocalVariableAndWriteNode) {
            return buildLocalAndVariableWrite((LocalVariableAndWriteNode) node);
        } else if (node instanceof LocalVariableOperatorWriteNode) {
            return buildLocalVariableOperatorWrite((LocalVariableOperatorWriteNode) node);
        } else if (node instanceof LocalVariableOrWriteNode) {
            return buildLocalOrVariableWrite((LocalVariableOrWriteNode) node);
        } else if (node instanceof LocalVariableReadNode) {
            return buildLocalVariableRead((LocalVariableReadNode) node);
        // LocalVariableTargetNode processed by multiple assignment
        } else if (node instanceof LocalVariableWriteNode) {
            return buildLocalVariableWrite((LocalVariableWriteNode) node);
        } else if (node instanceof MatchLastLineNode) {
            return buildMatchLastLine(result, (MatchLastLineNode) node);
        } else if (node instanceof MatchPredicateNode) {
            return buildMatchPredicate((MatchPredicateNode) node);
        } else if (node instanceof MatchRequiredNode) {
            return buildMatchRequired((MatchRequiredNode) node);
        } else if (node instanceof MatchWriteNode) {
            return buildMatchWrite(result, (MatchWriteNode) node);
        } else if (node instanceof MissingNode) {
            return buildMissing((MissingNode) node);
        } else if (node instanceof ModuleNode) {
            return buildModule((ModuleNode) node);
        } else if (node instanceof MultiWriteNode) {
            return buildMultiWriteNode((MultiWriteNode) node);
        } else if (node instanceof NextNode) {
            return buildNext((NextNode) node);
        } else if (node instanceof NilNode) {
            return nil();
        // NoKeywordsParameterNode processed by def                       // MISSING: NoKeywordsParameterNode
        } else if (node instanceof NumberedReferenceReadNode) {
            return buildNumberedReferenceRead((NumberedReferenceReadNode) node);
        // OptionalParameterNode processed by def
        } else if (node instanceof OrNode) {
            return buildOr((OrNode) node);
        // ParametersNode processed by def
        } else if (node instanceof ParenthesesNode) {
            return build(((ParenthesesNode) node).body);
        } else if (node instanceof PinnedExpressionNode) {
            return build(((PinnedExpressionNode) node).expression);
        } else if (node instanceof PinnedVariableNode) {
            return build(((PinnedVariableNode) node).variable);
        } else if (node instanceof PostExecutionNode) {
            return buildPostExecution((PostExecutionNode) node);
        } else if (node instanceof PreExecutionNode) {
            return buildPreExecution((PreExecutionNode) node);
        } else if (node instanceof ProgramNode) {
            return buildProgram((ProgramNode) node);
        } else if (node instanceof RangeNode) {
            return buildRange((RangeNode) node);
        } else if (node instanceof RationalNode) {
            return buildRational((RationalNode) node);
        } else if (node instanceof RedoNode) {
            return buildRedo((RedoNode) node);
        } else if (node instanceof RegularExpressionNode) {
            return buildRegularExpression((RegularExpressionNode) node);
        // RequiredDestructuredParamterNode, RequiredParameterNode processed by def
        } else if (node instanceof RescueModifierNode) {
            return buildRescueModifier((RescueModifierNode) node);
        // RescueNode handled by begin
        // RestParameterNode handled by def
        } else if (node instanceof RetryNode) {
            return buildRetry((RetryNode) node);
        } else if (node instanceof ReturnNode) {
            return buildReturn((ReturnNode) node);
        } else if (node instanceof SelfNode) {
            return buildSelf();
        } else if (node instanceof SingletonClassNode) {
            return buildSingletonClass((SingletonClassNode) node);
        } else if (node instanceof SourceEncodingNode) {
            return buildSourceEncoding();
        } else if (node instanceof SourceFileNode) {
            return buildSourceFile();
        } else if (node instanceof SourceLineNode) {
            return buildSourceLine(node);
        } else if (node instanceof SplatNode) {
            return buildSplat((SplatNode) node);
        } else if (node instanceof StatementsNode) {
            return buildStatements((StatementsNode) node);
        } else if (node instanceof StringNode) {
            return buildString((StringNode) node);
        } else if (node instanceof SuperNode) {
            return buildSuper(result, (SuperNode) node);
        } else if (node instanceof SymbolNode) {
            return buildSymbol((SymbolNode) node);
        } else if (node instanceof TrueNode) {
            return tru();
        } else if (node instanceof UndefNode) {
            return buildUndef((UndefNode) node);
        } else if (node instanceof UnlessNode) {
            return buildUnless(result, (UnlessNode) node);
        } else if (node instanceof UntilNode) {
            return buildUntil((UntilNode) node);
        // WhenNode processed by case
        } else if (node instanceof WhileNode) {
            return buildWhile((WhileNode) node);
        } else if (node instanceof XStringNode) {
            return buildXString(result, (XStringNode) node);
        } else if (node instanceof YieldNode) {
            return buildYield(result, (YieldNode) node);
        } else {
            throw new RuntimeException("Unhandled Node type: " + node);
        }
    }

    private Operand buildCallOperatorWrite(CallOperatorWriteNode node) {
        return buildOpAsgn(node.receiver, node.value, node.read_name, node.write_name, node.operator, node.isSafeNavigation());
    }

    private Operand buildImaginary(ImaginaryNode node) {
        return new Complex((ImmutableLiteral) build(node.numeric));
    }

    private Operand buildAliasGlobalVariable(AliasGlobalVariableNode node) {
        return buildVAlias(globalVariableName(node.new_name), globalVariableName(node.old_name));
    }

    private Operand buildAliasMethod(AliasMethodNode node) {
        return buildAlias(build(node.new_name), build(node.old_name));
    }

    private Operand buildAnd(AndNode node) {
        return buildAnd(build(node.left), () -> build(node.right), binaryType(node.left));
    }

    private Operand[] buildArguments(ArgumentsNode node) {
        return node == null ? Operand.EMPTY_ARRAY : buildNodeList(node.arguments);
    }

    private Operand buildAssocSplat(Variable result, AssocSplatNode node) {
        return build(result, node.value);
    }

    private Operand[] buildNodeList(Node[] list) {
        if (list == null || list.length == 0) return Operand.EMPTY_ARRAY;

        Operand[] args = new Operand[list.length];
        for (int i = 0; i < list.length; i++) {
            args[i] = build(list[i]);
        }

        return args;
    }

    private Operand buildArgumentsAsArgument(ArgumentsNode node) {
        Operand[] args = buildArguments(node);
        return args.length == 0 ? nil() : args.length == 1 ? args[0] : new Array(args);
    }

    private Operand buildArray(ArrayNode node) {
        Node[] children = node.elements;
        Operand[] elts = new Operand[children.length];
        Variable result = temp();
        int splatIndex = -1;
        Operand keywordRestSplat = null;

        for (int i = 0; i < children.length; i++) {
            Node child = children[i];

            if (child instanceof SplatNode) {
                int length = i - splatIndex - 1;

                if (length == 0) {
                    // FIXME: This is wasteful to force this all through argscat+empty array
                    if (splatIndex == -1) copy(result, new Array());
                    addResultInstr(new BuildCompoundArrayInstr(result, result, build(((SplatNode) child).expression), false, false));
                } else {
                    Operand[] lhs = new Operand[length];
                    System.arraycopy(elts, splatIndex + 1, lhs, 0, length);

                    // no actual splat until now.
                    if (splatIndex == -1) {
                        copy(result, new Array(lhs));
                    } else {
                        addResultInstr(new BuildCompoundArrayInstr(result, result, new Array(lhs), false, false));
                    }

                    addResultInstr(new BuildCompoundArrayInstr(result, result, build(((SplatNode) child).expression), false, false));
                }
                splatIndex = i;
            } else if (child instanceof KeywordHashNode) {
                keywordRestSplat = build(child);
                elts[i] = keywordRestSplat;
            } else {
                elts[i] = build(child);
            }
        }

        if (keywordRestSplat != null) {
            Variable test = addResultInstr(new RuntimeHelperCall(temp(), IS_HASH_EMPTY, new Operand[]{ keywordRestSplat }));
            if_else(test, tru(),
                    () -> copy(result, new Array(removeArg(elts))),
                    () -> copy(result, new Array(elts)));
            return result;
        }
        // FIXME: Can we just return the operand only in this case.
        // No splats present.  Just make a simple array Operand.
        if (splatIndex == -1) return copy(result, new Array(elts));

        int length = children.length - splatIndex - 1;
        if (length > 0) {
            Operand[] rhs = new Operand[length];
            System.arraycopy(elts, splatIndex + 1, rhs, 0, length);

            addResultInstr(new BuildCompoundArrayInstr(result, result, new Array(rhs), false, false));
        }

        return result;
    }

    // This method is called to build assignments for a multiple-assignment instruction
    protected void buildAssignment(Node node, Operand rhsVal) {
        if (node == null) return; // case of 'a, = something'

        if (node instanceof CallTargetNode) {
            buildAttrAssignAssignment(((CallTargetNode) node).receiver, ((CallTargetNode) node).name, Node.EMPTY_ARRAY, rhsVal);
        } else if (node instanceof IndexTargetNode) {
            Node[] arguments = ((IndexTargetNode) node).arguments == null ? Node.EMPTY_ARRAY : ((IndexTargetNode) node).arguments.arguments;
            buildAttrAssignAssignment(((IndexTargetNode) node).receiver, symbol("[]="), arguments, rhsVal);
        } else if (node instanceof ClassVariableTargetNode) {
            addInstr(new PutClassVariableInstr(classVarDefinitionContainer(), ((ClassVariableTargetNode) node).name, rhsVal));
        } else if (node instanceof ConstantPathTargetNode) {
            Operand parent = buildModuleParent(((ConstantPathTargetNode) node).parent);
            Node child = ((ConstantPathTargetNode) node).child;
            RubySymbol name;
            if (child instanceof ConstantTargetNode) {
                name = ((ConstantTargetNode) child).name;
            } else if (child instanceof ConstantReadNode) {
                name = ((ConstantReadNode) child).name;
            } else {
                throwSyntaxError(getLine(child), "Unknown child in ConstantPathTargetNode");
                name = null;
            }
            addInstr(new PutConstInstr(parent, name, rhsVal));
        } else if (node instanceof ConstantTargetNode) {
            addInstr(new PutConstInstr(getCurrentModuleVariable(), ((ConstantTargetNode) node).name, rhsVal));
        } else if (node instanceof LocalVariableTargetNode) {
            LocalVariableTargetNode variable = (LocalVariableTargetNode) node;
            copy(getLocalVariable(variable.name, variable.depth), rhsVal);
        } else if (node instanceof GlobalVariableTargetNode) {
            addInstr(new PutGlobalVarInstr(((GlobalVariableTargetNode) node).name, rhsVal));
        } else if (node instanceof InstanceVariableTargetNode) {
            addInstr(new PutFieldInstr(buildSelf(), ((InstanceVariableTargetNode) node).name, rhsVal));
        } else if (node instanceof MultiTargetNode) {
            Variable rhs = addResultInstr(new ToAryInstr(temp(), rhsVal));
            buildMultiAssignment(((MultiTargetNode) node).lefts, ((MultiTargetNode) node).rest, ((MultiTargetNode) node).rights, rhs);
        } else if (node instanceof MultiWriteNode) { // FIXME: Is this possible with Multitarget now existing?
            Variable rhs = addResultInstr(new ToAryInstr(temp(), rhsVal));
            buildMultiAssignment(((MultiWriteNode) node).lefts, ((MultiWriteNode) node).rest, ((MultiWriteNode) node).rights, rhs);
        } else if (node instanceof RequiredParameterNode) {
            RequiredParameterNode variable = (RequiredParameterNode) node;
            copy(getLocalVariable(variable.name, 0), rhsVal);
        } else if (node instanceof SplatNode) { // FIXME: Audit this...is there really a splat here and it has phatom expression field?
            buildSplat(rhsVal);
        } else {
            throw notCompilable("Can't build assignment node", node);
        }
    }

    private Operand buildModuleParent(Node parent) {
        return parent == null ? getCurrentModuleVariable() : build(parent);
    }

    @Override
    protected Operand[] buildAttrAssignCallArgs(Node argsNode, Operand[] rhs, boolean containsAssignment) {
        Operand[] args = buildCallArgs(argsNode, new int[] { 0 });
        rhs[0] = args[args.length - 1];
        return args;
    }

    public Operand buildAttrAssignAssignment(Node receiver, RubySymbol name, Node[] arguments, Operand value) {
        Operand obj = build(receiver);
        int[] flags = new int[] { 0 };
        Operand[] args = buildCallArgsArray(arguments, flags);
        args = addArg(args, value);
        addInstr(AttrAssignInstr.create(scope, obj, name, args, flags[0], scope.maybeUsingRefinements()));
        return value;
    }
    
    // FIXME(feature): optimization simplifying this from other globals
    private Operand buildBackReferenceRead(Variable result, BackReferenceReadNode node) {
        return buildGlobalVar(result, node.name);
    }

    private Operand buildBreak(BreakNode node) {
        return buildBreak(() -> node.arguments == null ? nil() : buildYieldArgs(node.arguments.arguments, new int[1]), getLine(node));
    }

    private Operand buildBegin(BeginNode node) {
        if (node.rescue_clause != null) {
            RescueNode rescue = node.rescue_clause;
            Node ensureBody = node.ensure_clause != null ? node.ensure_clause.statements : null;
            return buildEnsureInternal(node.statements, node.else_clause, rescue.exceptions, rescue.statements,
                    rescue.consequent, false, ensureBody, true, rescue.reference);
        } else if (node.ensure_clause != null) {
            EnsureNode ensure = node.ensure_clause;
            return buildEnsureInternal(node.statements, null, null, null, null, false, ensure.statements, false, null);
        }
        return build(node.statements);
    }

    private Operand buildBlock(BlockNode node) {
        StaticScope staticScope = createStaticScopeFrom(node.locals, StaticScope.Type.BLOCK);
        Signature signature = calculateSignature(node.parameters);
        staticScope.setSignature(signature);
        return buildIter(node.parameters, node.body, staticScope, signature, getLine(node), getEndLine(node));
    }

    protected Variable receiveBlockArg(Variable v, Operand argsArray, int argIndex, boolean isSplat) {
        if (argsArray != null) {
            // We are in a nested receive situation -- when we are not at the root of a masgn tree
            // Ex: We are trying to receive (b,c) in this example: "|a, (b,c), d| = ..."
            if (isSplat) addInstr(new RestArgMultipleAsgnInstr(v, argsArray, argIndex));
            else addInstr(new ReqdArgMultipleAsgnInstr(v, argsArray, argIndex));
        } else {
            // argsArray can be null when the first node in the args-node-ast is a multiple-assignment
            // For example, for-nodes
            // FIXME: We can have keywords here but this is more complicated to get here
            Variable keywords = copy(UndefinedValue.UNDEFINED);
            addInstr(isSplat ? new ReceiveRestArgInstr(v, keywords, argIndex, argIndex) : new ReceivePreReqdArgInstr(v, keywords, argIndex));
        }

        return v;
    }

    @Override
    protected void receiveForArgs(Node node) {
        Variable keywords = copy(temp(), UndefinedValue.UNDEFINED);

        // FIXME: I think this should rip out receivePre and stop sharingh with method defs
        // FIXME: pattern of use seems to be recv pre then assign value so this may be done with less if (or at least through separate methods between value and receiving the value from args).
        if (node instanceof MultiTargetNode) { // for loops
            buildBlockArgsAssignment(node, null, 0, false);
        } else if (node instanceof ClassVariableTargetNode || node instanceof LocalVariableTargetNode ||
                node instanceof InstanceVariableTargetNode || node instanceof ConstantTargetNode) {
            receivePreArg(node, keywords, 0);
        } else {
            throw notCompilable("missing arg processing for `for`", node);
        }
    }

    private ArgumentDescriptor[] parametersToArgumentDescriptors(NumberedParametersNode node) {
        ArgumentDescriptor[] descriptors = new ArgumentDescriptor[node.maximum];

        for (int i = 0; i < node.maximum; i++) {
            descriptors[i] = new ArgumentDescriptor(ArgumentType.req, symbol("_" + (i + 1)));
        }

        return descriptors;
    }

    public void receiveBlockArgs(Node node) {
        if (node == null) return;

        if (node instanceof NumberedParametersNode) {
            NumberedParametersNode params = (NumberedParametersNode) node;
            ((IRClosure) scope).setArgumentDescriptors(parametersToArgumentDescriptors((NumberedParametersNode) node));
            Variable keywords = addResultInstr(new ReceiveKeywordsInstr(temp(), true, true));

            for (int i = 0; i < params.maximum; i++) {
                RubySymbol name = symbol("_" + (i + 1));
                addInstr(new ReceivePreReqdArgInstr(argumentResult(name), keywords, i));
            }
            addInstr(new CheckArityInstr(params.maximum, 0, false, params.maximum, keywords));
        } else {
            // FIXME: Missing locals?  Not sure how we handle those but I would have thought with a scope?
            buildParameters(((BlockParametersNode) node).parameters);

            ((IRClosure) scope).setArgumentDescriptors(createArgumentDescriptor());
        }
    }

    private void buildBlockArgsAssignment(Node node, Operand argsArray, int argIndex, boolean isSplat) {
        if (node instanceof CallNode) { // attribute assignment: a[0], b = 1, 2
            buildAttrAssignAssignment(((CallNode) node).receiver, ((CallNode) node).name, ((CallNode) node).arguments.arguments,
                    receiveBlockArg(temp(), argsArray, argIndex, isSplat));
        } else if (node instanceof LocalVariableTargetNode) {
            LocalVariableTargetNode lvar = (LocalVariableTargetNode) node;
            receiveBlockArg(getLocalVariable(lvar.name, lvar.depth), argsArray, argIndex, isSplat);
        } else if (node instanceof ClassVariableTargetNode) {
            addInstr(new PutClassVariableInstr(classVarDefinitionContainer(), ((ClassVariableTargetNode) node).name,
                    receiveBlockArg(temp(), argsArray, argIndex, isSplat)));
        } else if (node instanceof ConstantTargetNode) {
            putConstant(((ConstantTargetNode) node).name, receiveBlockArg(temp(), argsArray, argIndex, isSplat));
        } else if (node instanceof GlobalVariableTargetNode) {
            addInstr(new PutGlobalVarInstr(((GlobalVariableTargetNode) node).name,
                    receiveBlockArg(temp(), argsArray, argIndex, isSplat)));
        } else if (node instanceof InstanceVariableTargetNode) {
            addInstr(new PutFieldInstr(buildSelf(), ((InstanceVariableTargetNode) node).name,
                    receiveBlockArg(temp(), argsArray, argIndex, isSplat)));
        } else if (node instanceof MultiTargetNode) {
            MultiTargetNode multi = (MultiTargetNode) node;
            for (int i = 0; i < multi.lefts.length; i++) {
                buildBlockArgsAssignment(multi.lefts[i], null, i, false);
            }

            int postIndex = multi.lefts.length;
            if (multi.rest != null) {
                buildBlockArgsAssignment(multi.rest, null, postIndex, true);
                postIndex++;
            }
            for (int i = 0; i < multi.rights.length; i++) {
                buildBlockArgsAssignment(multi.rights[i], null, postIndex + i, false);
            }
        } else if (node instanceof SplatNode) {
            // FIXME: we don't work in legacy either?
        } else if (node instanceof ImplicitRestNode) {
        } else {
            throw notCompilable("Can't build assignment node", node);
        }
    }

    private Operand buildBlockArgument(BlockArgumentNode node) {
        if (node.expression instanceof SymbolNode && !scope.maybeUsingRefinements()) {
            return new SymbolProc(symbol(((SymbolNode) node.expression)));
        } else if (node.expression == null) {
            return getYieldClosureVariable();
        }
        return build(node.expression);
    }

    private Operand buildCall(Variable resultArg, CallNode node, RubySymbol name) {
        return buildCall(resultArg, node, name, null, null);
    }

    protected Operand buildLazyWithOrder(CallNode node, Label lazyLabel, Label endLabel, boolean preserveOrder) {
        Operand value = buildCall(null, node, node.name, lazyLabel, endLabel);

        // FIXME: missing !(value instanceof ImmutableLiteral) which will force more copy instr
        // We need to preserve order in cases (like in presence of assignments) except that immutable
        // literals can never change value so we can still emit these out of order.
        return preserveOrder ? copy(value) : value;
    }

    // FIXME: This would be nice to combine in some form with AST side but it requires some pre-processing since prism merged all call types into a single node.
    // We do name processing outside of this rather than from the node to support stripping '=' off of opelasgns
    private Operand buildCall(Variable resultArg, CallNode node, RubySymbol name, Label lazyLabel, Label endLabel) {
        Variable result = resultArg == null ? temp() : resultArg;

        if (node.isVariableCall()) return _call(result, VARIABLE, buildSelf(), name);

        CallType callType = determineCallType(node.receiver);
        String id = name.idString();

        if (node.isAttributeWrite()) return buildAttrAssign(result, node.receiver, node.arguments, node.block, node.name, node.isSafeNavigation(), containsVariableAssignment(node));

        if (callType != CallType.FUNCTIONAL && Options.IR_STRING_FREEZE.load()) {
            // Frozen string optimization: check for "string".freeze
            if (node.receiver instanceof StringNode && (id.equals("freeze") || id.equals("-@"))) {
                return new FrozenString(bytelistFrom((StringNode) node.receiver), CR_UNKNOWN, scope.getFile(), getLine(node.receiver));
            }
        }

        if (node.isSafeNavigation()) {
            if (lazyLabel == null) {
                lazyLabel = getNewLabel();
                endLabel = getNewLabel();
            }
        }

        // The receiver has to be built *before* call arguments are built
        // to preserve expected code execution order
        // FIXME: this always builds with order (lacking containsVariableAssignment() in prism).
        Operand receiver;
        if (callType == CallType.FUNCTIONAL) {
            receiver = buildSelf();
        } else if (node.receiver instanceof CallNode && ((CallNode) node.receiver).isSafeNavigation()) {
            receiver = buildLazyWithOrder((CallNode) node.receiver, lazyLabel, endLabel, true);
        } else {
            receiver = buildWithOrder(node.receiver, true);
        }

        if (node.isSafeNavigation()) addInstr(new BNilInstr(lazyLabel, receiver));

        // FIXME: Missing arrayderef opti logic

        createCall(result, receiver, callType, name, node.arguments, node.block, getLine(node), node.hasNewLineFlag());

        if (node.isSafeNavigation()) {
            addInstr(new JumpInstr(endLabel));
            addInstr(new LabelInstr(lazyLabel));
            addInstr(new CopyInstr(result, nil()));
            addInstr(new LabelInstr(endLabel));
        }

        return result;
    }

    private Operand buildCallAndWrite(CallAndWriteNode node) {
        return buildOpAsgn(node.receiver, node.value, node.read_name, node.write_name, symbol(AMPERSAND_AMPERSAND), node.isSafeNavigation());
    }

    @Override
    protected Operand[] buildCallArgs(Node args, int[] flags) {
        return buildCallArgsArray(((ArgumentsNode) args).arguments, flags);

    }
    protected Operand[] buildCallArgsArray(Node[] children, int[] flags) {
        int numberOfArgs = children.length;
        // FIXME: hack
        if (numberOfArgs > 0 && children[numberOfArgs - 1] instanceof BlockArgumentNode) {
            Node[] temp = children;
            numberOfArgs--;
            children = new Node[numberOfArgs];
            System.arraycopy(temp, 0, children, 0, numberOfArgs);
        }
        Operand[] builtArgs = new Operand[numberOfArgs];
        boolean hasAssignments = containsVariableAssignment(children);

        for (int i = 0; i < numberOfArgs; i++) {
            Node child = children[i];

            if (child instanceof SplatNode) {
                flags[0] |= CALL_SPLATS;
                builtArgs[i] = new Splat(addResultInstr(new BuildSplatInstr(temp(), build(((SplatNode) child).expression), true)));
            } else if (child instanceof KeywordHashNode && i == numberOfArgs - 1) {
                builtArgs[i] = buildCallKeywordArguments((KeywordHashNode) children[i], flags); // FIXME: here and possibly AST make isKeywordsHash() method.
            } else if (child instanceof ForwardingArgumentsNode) {
                Operand rest = buildSplat(getLocalVariable(symbol(FWD_REST), scope.getStaticScope().isDefined("*")));
                Operand kwRest = getLocalVariable(symbol(FWD_KWREST), scope.getStaticScope().isDefined("**"));
                Variable check = addResultInstr(new RuntimeHelperCall(temp(), HASH_CHECK, new Operand[] { kwRest }));
                Variable ary = addResultInstr(new BuildCompoundArrayInstr(temp(), rest, check, true, true));
                builtArgs[i] = new Splat(buildSplat(ary));
                flags[0] |= CALL_KEYWORD | CALL_KEYWORD_REST | CALL_SPLATS;
            } else {
                builtArgs[i] = buildWithOrder(children[i], hasAssignments);
            }
        }

        return builtArgs;
    }

    protected Operand buildCallKeywordArguments(KeywordHashNode node, int[] flags) {
        flags[0] |= CALL_KEYWORD;

        if (hasOnlyRestKwargs(node.elements)) return buildRestKeywordArgs(node, flags);

        return buildHash(node.elements, containsVariableAssignment(node.elements));
    }

    private Operand buildCallOrWrite(CallOrWriteNode node) {
        return buildOpAsgn(node.receiver, node.value, node.read_name, node.write_name, symbol(OR_OR), node.isSafeNavigation());
    }

    private Operand buildCase(CaseNode node) {
        return buildCase(node.predicate, node.conditions, node.consequent);
    }

    private Operand buildCaseMatch(CaseMatchNode node) {
        return buildPatternCase(node.predicate, node.conditions, node.consequent);
    }

    private Operand buildClass(ClassNode node) {
        return buildClass(determineBaseName(node.constant_path), node.superclass, node.constant_path,
                node.body, createStaticScopeFrom(node.locals, StaticScope.Type.LOCAL), getLine(node), getEndLine(node));
    }

    private Operand buildClassVariableOperatorWrite(ClassVariableOperatorWriteNode node) {
        Operand lhs = buildClassVar(temp(), node.name);
        Operand rhs = build(node.value);
        Variable value = call(temp(), lhs, node.operator, rhs);
        addInstr(new PutClassVariableInstr(classVarDefinitionContainer(), node.name, value));
        return value;
    }

    private Operand buildInstanceVariableOperatorWrite(InstanceVariableOperatorWriteNode node) {
        Operand lhs = buildInstVar(node.name);
        Operand rhs = build(node.value);
        Variable value = call(temp(), lhs, node.operator, rhs);
        addInstr(new PutFieldInstr(buildSelf(), node.name, value));
        return value;
    }

    private Operand buildLocalVariableOperatorWrite(LocalVariableOperatorWriteNode node) {
        int depth = staticScope.isDefined(node.name.idString()) >> 16;
        Variable lhs = getLocalVariable(node.name, depth);
        Operand rhs = build(node.value);
        Variable value = call(lhs, lhs, node.operator, rhs);
        return value;
    }

    private Operand buildClassAndVariableWrite(ClassVariableAndWriteNode node) {
        return buildOpAsgnAnd(() -> addResultInstr(new GetClassVariableInstr(temp(), classVarDefinitionContainer(), node.name)),
                () -> (buildClassVarAsgn(node.name, node.value)));
    }

    private Operand buildClassOrVariableWrite(ClassVariableOrWriteNode node) {
        return buildOpAsgnOrWithDefined(node,
                (result) -> addInstr(new GetClassVariableInstr((Variable) result, classVarDefinitionContainer(), node.name)),
                () -> (buildClassVarAsgn(node.name, node.value)));
    }

    private Operand buildClassVariableRead(Variable result, ClassVariableReadNode node) {
        return buildClassVar(result, node.name);
    }

    private Operand buildClassVariableWrite(ClassVariableWriteNode node) {
        return buildClassVarAsgn(node.name, node.value);
    }

    @Override
    protected Operand buildColon2ForConstAsgnDeclNode(Node lhs, Variable valueResult, boolean constMissing) {
        Variable leftModule = temp();
        ConstantPathNode colon2Node = (ConstantPathNode) lhs;
        RubySymbol name = symbol(determineBaseName(colon2Node));
        Operand leftValue;
        if (colon2Node.parent == null)  {
            leftValue = getManager().getObjectClass();   // ::Foo
        } else {
            leftValue = build(colon2Node.parent);
        }
        // FIXME: ::Foo should be able to eliminate this variable reference
        copy(leftModule, leftValue);
        addInstr(new SearchModuleForConstInstr(valueResult, leftModule, name, false, constMissing));

        return leftModule;
    }

    private Operand buildConstantAndWrite(ConstantAndWriteNode node) {
        return buildOpAsgnAnd(() -> addResultInstr(new SearchConstInstr(temp(), CurrentScope.INSTANCE, node.name, false)),
                () -> (putConstant(node.name, build(node.value))));
    }

    private Operand buildConstantOperatorWrite(ConstantOperatorWriteNode node) {
        Operand lhs = searchConst(temp(), node.name);
        Operand rhs = build(node.value);
        Variable value = call(temp(), lhs, node.operator, rhs);
        putConstant(buildSelf(), node.name, value);
        return value;
    }

    private Operand buildConstantOrWrite(ConstantOrWriteNode node) {
        return buildOpAsgnOrWithDefined(node,
                (result) -> addInstr(new SearchConstInstr((Variable) result, CurrentScope.INSTANCE, node.name, false)),
                () -> (putConstant(node.name, build(node.value))));
    }

    private Operand buildConstantOrWritePath(ConstantPathOrWriteNode node) {
        // FIXME: unify with AST
        RubySymbol name = ((ConstantReadNode) node.target.child).name;
        Variable result = temp();
        Label falseCheck = getNewLabel();
        Label done = getNewLabel();
        Label assign = getNewLabel();
        // FIXME: this is semi-duplicated from buildConstantPath since we want out param of module and value returned to result
        Operand module = node.target.parent == null ? getManager().getObjectClass() : build(node.target.parent);
        searchModuleForConstNoFrills(result, module, name);
        addInstr(BNEInstr.create(falseCheck, result, UndefinedValue.UNDEFINED));
        addInstr(new JumpInstr(assign));
        addInstr(new LabelInstr(falseCheck));
        addInstr(BNEInstr.create(done, result, fals()));
        addInstr(new LabelInstr(assign));
        Operand rhsValue = build(node.value);
        copy(result, rhsValue);
        addInstr(new PutConstInstr(module, name, rhsValue));
        addInstr(new LabelInstr(done));
        return result;
    }

    private Operand buildConstantPath(Variable result, ConstantPathNode node) {
        return buildConstantPath(result, ((ConstantReadNode) node.child).name, node.parent);
    }

    private Operand buildConstantPath(Variable result, RubySymbol name, Node parent) {
        Operand where = parent == null ? getManager().getObjectClass() : build(parent);
        return searchModuleForConst(result, where, name);
    }

    private Operand buildConstantPathAndWrite(ConstantPathAndWriteNode node) {
        return buildOpAsgnConstDeclAnd(node.target, node.value, symbol(determineBaseName(node.target)));
    }

    private Operand buildConstantPathOperatorWrite(ConstantPathOperatorWriteNode node) {
        return buildOpAsgnConstDecl(node.target, node.value, node.operator);
    }

    private Operand buildConstantPathOrWrite(ConstantPathOrWriteNode node) {
        return buildOpAsgnConstDeclOr(node.target, node.value, symbol(determineBaseName(node.target)));
    }

    private Operand buildConstantRead(ConstantReadNode node) {
        return addResultInstr(new SearchConstInstr(temp(), CurrentScope.INSTANCE, node.name, false));
    }

    private Operand buildConstantWrite(ConstantWriteNode node) {
        return putConstant(node.name, build(node.value));
    }

    private Operand buildConstantWritePath(ConstantPathWriteNode node) {
        return buildConstantWritePath(node.target, build(node.value));
    }

    // Multiple assignments provide the value otherwise it is grabbed from .value on the node.
    private Operand buildConstantWritePath(ConstantPathNode path, Operand value) {
        return putConstant(buildModuleParent(path.parent), ((ConstantReadNode) path.child).name, value);
    }

    private Operand buildDef(DefNode node) {
        // FIXME: due to how lazy methods work we need this set on method before we actually parse the method.
        StaticScope staticScope = createStaticScopeFrom(node.locals, StaticScope.Type.LOCAL);
        staticScope.setSignature(calculateSignature(node.parameters));
        LazyMethodDefinition def = new LazyMethodDefinitionPrism(source, nodeSource, encoding, node);

        if (node.receiver == null) {
            return buildDefn(defineNewMethod(def, node.name.getBytes(), getLine(node), staticScope, true));
        } else {
            return buildDefs(node.receiver, defineNewMethod(def, node.name.getBytes(), getLine(node), staticScope, false));
        }
    }

    private Operand buildDefined(DefinedNode node) {
        return buildGetDefinition(node.value);
    }

    private Operand buildElse(ElseNode node) {
        return buildStatements(node.statements);
    }

    private Operand buildFlipFlop(FlipFlopNode node) {
        return buildFlip(node.left, node.right, node.isExcludeEnd());
    }

    // FIXME: Do we need warn or will YARP provide it.
    private Operand buildFloat(FloatNode node) {
        String number = bytelistFrom(node).toString();

        // FIXME: This is very expensive but numeric values is still be decided in Prism.
        IRubyObject fl = RubyKernel.new_float(getManager().getRuntime().getCurrentContext(),
                getManager().getRuntime().newString(number), false);

        return new Float(((RubyFloat) fl).getDoubleValue());
    }

    private Operand buildFor(ForNode node) {
        return buildFor(node.collection, node.index, node.statements, scope.getStaticScope(),
                calculateSignatureFor(node.index), getLine(node), getEndLine(node));
    }

    private Operand buildForwardingSuper(Variable result, ForwardingSuperNode node) {
        return buildZSuper(result, node.block);
    }

    public Operand buildGetArgumentDefinition(final ArgumentsNode node, String type) {
        if (node == null) return new MutableString(type);

        Operand rv = new FrozenString(type);
        boolean failPathReqd = false;
        Label failLabel = getNewLabel();
        for(Node arg: node.arguments) {
            Operand def = buildGetDefinition(arg);
            if (def == nil()) { // Optimization!
                rv = nil();
                break;
            } else if (!def.hasKnownValue()) { // Optimization!
                failPathReqd = true;
                addInstr(createBranch(def, nil(), failLabel));
            }
        }

        return failPathReqd ? buildDefnCheckIfThenPaths(failLabel, rv) : rv;

    }

    // FIXME: implementation of @@a ||= 1 uses getDefinition to determine it is defined but defined?(@@a ||= 1) is
    // always defined as "assignment".
    protected Operand buildGetDefinition2(Node node) {
        if (node instanceof ClassVariableOrWriteNode) {
            return buildClassVarGetDefinition(((ClassVariableOrWriteNode) node).name);
        } else if (node instanceof GlobalVariableOrWriteNode) {
            return buildGlobalVarGetDefinition(((GlobalVariableOrWriteNode) node).name);
        } else if (node instanceof ConstantOrWriteNode) {
            return buildConstantGetDefinition(((ConstantOrWriteNode) node).name);
        }

        return buildGetDefinition(node);
    }
    @Override
    protected Operand buildGetDefinition(Node node) {
        if (node == null) return new FrozenString("expression");

        if (node instanceof ClassVariableWriteNode ||
                node instanceof ClassVariableAndWriteNode ||
                node instanceof ClassVariableOperatorWriteNode || node instanceof ClassVariableOrWriteNode ||
                node instanceof ConstantAndWriteNode || node instanceof ConstantOrWriteNode ||
                node instanceof ConstantPathWriteNode || node instanceof ConstantWriteNode ||
                node instanceof InstanceVariableAndWriteNode || node instanceof InstanceVariableOrWriteNode ||
                node instanceof InstanceVariableOperatorWriteNode ||
                node instanceof LocalVariableWriteNode ||
                node instanceof LocalVariableAndWriteNode || node instanceof LocalVariableOrWriteNode ||
                node instanceof LocalVariableOperatorWriteNode || node instanceof ConstantOperatorWriteNode ||
                node instanceof GlobalVariableOrWriteNode || node instanceof GlobalVariableAndWriteNode ||
                node instanceof GlobalVariableWriteNode || node instanceof GlobalVariableOperatorWriteNode ||
                node instanceof MultiWriteNode ||
                node instanceof InstanceVariableWriteNode || node instanceof IndexAndWriteNode ||
                node instanceof IndexOrWriteNode || node instanceof IndexOperatorWriteNode ||
                node instanceof CallAndWriteNode || node instanceof CallOrWriteNode ||
                node instanceof CallOperatorWriteNode) {
            return new FrozenString(DefinedMessage.ASSIGNMENT.getText());
        } else if (node instanceof OrNode || node instanceof AndNode ||
                node instanceof InterpolatedRegularExpressionNode || node instanceof InterpolatedStringNode) {
            return new FrozenString(DefinedMessage.EXPRESSION.getText());
        } else if (node instanceof ParenthesesNode) {
            if (((ParenthesesNode) node).body instanceof StatementsNode) {
                StatementsNode statements = (StatementsNode) ((ParenthesesNode) node).body;
                switch (statements.body.length) {
                    case 0: return nil();
                    case 1: return buildGetDefinition(statements.body[0]);
                }
            }

            return new FrozenString(DefinedMessage.EXPRESSION.getText());
        } else if (node instanceof FalseNode) {
            return new FrozenString(DefinedMessage.FALSE.getText());
        } else if (node instanceof LocalVariableReadNode) {
            return new FrozenString(DefinedMessage.LOCAL_VARIABLE.getText());
        } else if (node instanceof MatchPredicateNode || node instanceof MatchRequiredNode) {
            return new FrozenString(DefinedMessage.METHOD.getText());
        } else if (node instanceof NilNode) {
            return new FrozenString(DefinedMessage.NIL.getText());
        } else if (node instanceof SelfNode) {
            return new FrozenString(DefinedMessage.SELF.getText());
        } else if (node instanceof TrueNode) {
            return new FrozenString(DefinedMessage.TRUE.getText());
        } else if (node instanceof StatementsNode) {
            Node[] array = ((StatementsNode) node).body;
            Label undefLabel = getNewLabel();
            Label doneLabel = getNewLabel();

            Variable tmpVar = temp();
            for (Node elt : array) {
                Operand result = buildGetDefinition(elt);

                addInstr(createBranch(result, nil(), undefLabel));
            }

            addInstr(new CopyInstr(tmpVar, new FrozenString(DefinedMessage.EXPRESSION.getText())));
            addInstr(new JumpInstr(doneLabel));
            addInstr(new LabelInstr(undefLabel));
            addInstr(new CopyInstr(tmpVar, nil()));
            addInstr(new LabelInstr(doneLabel));

            return tmpVar;
        } else if (node instanceof GlobalVariableReadNode) {
            return buildGlobalVarGetDefinition(((GlobalVariableReadNode) node).name);
        } else if (node instanceof BackReferenceReadNode) {
            return addResultInstr(
                    new RuntimeHelperCall(
                            temp(),
                            IS_DEFINED_BACKREF,
                            new Operand[] {new FrozenString(DefinedMessage.GLOBAL_VARIABLE.getText())}
                    )
            );
        } else if (node instanceof NumberedReferenceReadNode) {
            return addResultInstr(new RuntimeHelperCall(
                    temp(),
                    IS_DEFINED_NTH_REF,
                    new Operand[] {
                            fix(((NumberedReferenceReadNode) node).number),
                            new FrozenString(DefinedMessage.GLOBAL_VARIABLE.getText())
                    }
            ));
        } else if (node instanceof InstanceVariableReadNode) {
            return buildInstVarGetDefinition(((InstanceVariableReadNode) node).name);
        } else if (node instanceof InstanceVariableOrWriteNode) {
            return buildInstVarGetDefinition(((InstanceVariableOrWriteNode) node).name);
        } else if (node instanceof ClassVariableReadNode) {
            return buildClassVarGetDefinition(((ClassVariableReadNode) node).name);
        } else if (node instanceof SuperNode) {
            Label undefLabel = getNewLabel();
            Variable tmpVar = addResultInstr(
                    new RuntimeHelperCall(
                            temp(),
                            IS_DEFINED_SUPER,
                            new Operand[]{
                                    buildSelf(),
                                    new FrozenString(DefinedMessage.SUPER.getText())
                            }
                    )
            );
            addInstr(createBranch(tmpVar, nil(), undefLabel));
            Operand superDefnVal = buildGetArgumentDefinition(((SuperNode) node).arguments, DefinedMessage.SUPER.getText());
            return buildDefnCheckIfThenPaths(undefLabel, superDefnVal);
        } else if (node instanceof ForwardingSuperNode) {
            return addResultInstr(
                    new RuntimeHelperCall(temp(), IS_DEFINED_SUPER,
                            new Operand[] { buildSelf(), new FrozenString(DefinedMessage.SUPER.getText()) }));
        } else if (node instanceof CallNode) {
            CallNode call = (CallNode) node;

            if (call.receiver == null && call.arguments == null) { // VCALL
                return addResultInstr(
                        new RuntimeHelperCall(temp(), IS_DEFINED_METHOD,
                                new Operand[]{ buildSelf(), new FrozenString(call.name), fals(), new FrozenString(DefinedMessage.METHOD.getText()) }));
            }

            String type = DefinedMessage.METHOD.getText();

            if (call.receiver == null) { // FCALL
                /* ------------------------------------------------------------------
                 * Generate IR for:
                 *    r = self/receiver
                 *    mc = r.metaclass
                 *    return mc.methodBound(meth) ? buildGetArgumentDefn(..) : false
                 * ----------------------------------------------------------------- */
                Label undefLabel = getNewLabel();
                Variable tmpVar = addResultInstr(
                        new RuntimeHelperCall(
                                temp(),
                                IS_DEFINED_METHOD,
                                new Operand[]{
                                        buildSelf(),
                                        new Symbol(call.name),
                                        fals(),
                                        new FrozenString(DefinedMessage.METHOD.getText())
                                }
                        )
                );
                addInstr(createBranch(tmpVar, nil(), undefLabel));
                Operand argsCheckDefn = buildGetArgumentDefinition(((CallNode) node).arguments, type);
                return buildDefnCheckIfThenPaths(undefLabel, argsCheckDefn);
            } else { // CALL
                // protected main block
                CodeBlock protectedCode = new CodeBlock() {
                    public Operand run() {
                        final Label undefLabel = getNewLabel();
                        Operand receiverDefn = buildGetDefinition(call.receiver);
                        addInstr(createBranch(receiverDefn, nil(), undefLabel));
                        Variable tmpVar = temp();
                        addInstr(new RuntimeHelperCall(tmpVar, IS_DEFINED_CALL,
                                new Operand[]{
                                        build(call.receiver),
                                        new Symbol(call.name),
                                        new FrozenString(DefinedMessage.METHOD.getText())
                                }));
                        return buildDefnCheckIfThenPaths(undefLabel, tmpVar);
                    }
                };

                // Try verifying definition, and if we get an exception, throw it out, and return nil
                return protectCodeWithRescue(protectedCode, () -> nil());
            }
        } else if (node instanceof YieldNode) {
            return buildDefinitionCheck(new BlockGivenInstr(temp(), getYieldClosureVariable()), DefinedMessage.YIELD.getText());
        } else if (node instanceof SuperNode) {
            // FIXME: Current code missing way to tell zsuper from super
            return addResultInstr(
                    new RuntimeHelperCall(temp(), IS_DEFINED_SUPER,
                            new Operand[]{buildSelf(), new FrozenString(DefinedMessage.SUPER.getText())}));
        } else if (node instanceof ConstantReadNode) {
            return buildConstantGetDefinition(((ConstantReadNode) node).name);
        } else if (node instanceof ConstantPathNode) {
            // SSS FIXME: Is there a reason to do this all with low-level IR?
            // Can't this all be folded into a Java method that would be part
            // of the runtime library, which then can be used by buildDefinitionCheck method above?
            // This runtime library would be used both by the interpreter & the compiled code!

            ConstantPathNode path = (ConstantPathNode) node;

            final RubySymbol name = ((ConstantReadNode) path.child).name;
            final Variable errInfo = temp();

            // store previous exception for restoration if we rescue something
            addInstr(new GetErrorInfoInstr(errInfo));

            CodeBlock protectedCode = () -> {
                if (path.parent == null) { // colon3
                    return addResultInstr(
                            new RuntimeHelperCall(
                                    temp(),
                                    IS_DEFINED_CONSTANT_OR_METHOD,
                                    new Operand[]{
                                            getManager().getObjectClass(),
                                            new FrozenString(name),
                                            new FrozenString(DefinedMessage.CONSTANT.getText()),
                                            new FrozenString(DefinedMessage.METHOD.getText())
                                    }
                            )
                    );
                }

                Label bad = getNewLabel();
                Label done = getNewLabel();
                Variable result = temp();
                Operand test = buildGetDefinition(path.parent);
                addInstr(createBranch(test, nil(), bad));
                Operand lhs = build(path.parent);
                addInstr(
                        new RuntimeHelperCall(
                                result,
                                IS_DEFINED_CONSTANT_OR_METHOD,
                                new Operand[]{
                                        lhs,
                                        new FrozenString(name),
                                        new FrozenString(DefinedMessage.CONSTANT.getText()),
                                        new FrozenString(DefinedMessage.METHOD.getText())
                                }
                        )
                );
                addInstr(new JumpInstr(done));
                addInstr(new LabelInstr(bad));
                addInstr(new CopyInstr(result, nil()));
                addInstr(new LabelInstr(done));

                return result;
            };

            // Try verifying definition, and if we get an JumpException exception, process it with the rescue block above
            return protectCodeWithRescue(protectedCode, () -> {
                addInstr(new RestoreErrorInfoInstr(errInfo)); // ignore and restore (we don't care about error)
                return nil();
            });
        } else if (node instanceof ArrayNode) {
            ArrayNode array = (ArrayNode) node;
            Label undefLabel = getNewLabel();
            Label doneLabel = getNewLabel();

            Variable tmpVar = temp();
            for (Node elt : array.elements) {
                Operand result = buildGetDefinition(elt);

                addInstr(createBranch(result, nil(), undefLabel));
            }

            addInstr(new CopyInstr(tmpVar, new FrozenString(DefinedMessage.EXPRESSION.getText())));
            addInstr(new JumpInstr(doneLabel));
            addInstr(new LabelInstr(undefLabel));
            addInstr(new CopyInstr(tmpVar, nil()));
            addInstr(new LabelInstr(doneLabel));

            return tmpVar;
        }

        return new FrozenString("expression");
    }

    private Operand buildGlobalVariableRead(Variable result, GlobalVariableReadNode node) {
        return buildGlobalVar(result, node.name);
    }

    private Operand buildGlobalVariableOperatorWrite(GlobalVariableOperatorWriteNode node) {
        Operand lhs = buildGlobalVar(temp(), node.name);
        Operand rhs = build(node.value);
        Variable value = call(temp(), lhs, node.operator, rhs);
        addInstr(new PutGlobalVarInstr(node.name, value));
        return value;
    }

    private Operand buildGlobalVariableAndWrite(GlobalVariableAndWriteNode node) {
        return buildOpAsgnAnd(() -> buildGlobalVar(temp(), node.name),
                () -> buildGlobalAsgn(node.name, node.value));
    }

    private Operand buildGlobalVariableOrWrite(GlobalVariableOrWriteNode node) {
        return buildOpAsgnOrWithDefined(node,
                (result) -> buildGlobalVar((Variable) result, node.name),
                () -> buildGlobalAsgn(node.name, node.value));
    }

    private Operand buildGlobalVariableWrite(GlobalVariableWriteNode node) {
        return buildGlobalAsgn(node.name, node.value);
    }

    private Operand buildHash(Node[] elements, boolean hasAssignments) {
        List<KeyValuePair<Operand, Operand>> args = new ArrayList<>();
        Set<Object> keysHack = new HashSet<>(); // Remove once prism #2005 is fixed.
        Variable hash = null;
        // Duplication checks happen when **{} are literals and not **h variable references.
        Operand duplicateCheck = fals();

        // pair is AssocNode or AssocSplatNode
        for (Node pair: elements) {
            Operand keyOperand;

            if (pair instanceof AssocNode) {
                Node key = ((AssocNode) pair).key;

                if (key instanceof StringNode) {  // FIXME: #2045 in prism about whether it should be marked frozen by parser.
                    keyOperand = buildFrozenString((StringNode) key);

                    String hack = ((FrozenString) keyOperand).getByteList().toString();
                    if (!keysHack.add(hack)) getManager().getRuntime().getWarnings().warn("key \"" + hack + "\" is duplicated and overwritten on line " + (getLine(key) + 1));
                } else {
                    keyOperand = build(key);
                    if (keyOperand instanceof Symbol) {
                        String hack = ((Symbol) keyOperand).getString();
                        if (!keysHack.add(hack)) getManager().getRuntime().getWarnings().warn("key :" + hack + " is duplicated and overwritten on line " + (getLine(key) + 1));
                    } else if (keyOperand instanceof Fixnum) {
                        long hack = ((Fixnum) keyOperand).value;
                        if (!keysHack.add(new Long(hack))) getManager().getRuntime().getWarnings().warn("key " + hack + " is duplicated and overwritten on line " + (getLine(key) + 1));
                    } else if (keyOperand instanceof Float) {
                        double hack = ((Float) keyOperand).value;
                        if (!keysHack.add(new Double(hack))) getManager().getRuntime().getWarnings().warn("key " + hack + " is duplicated and overwritten on line " + (getLine(key) + 1));
                    }
                }
                args.add(new KeyValuePair<>(keyOperand, buildWithOrder(((AssocNode) pair).value, hasAssignments)));
            } else {  // AssocHashNode
                AssocSplatNode assoc = (AssocSplatNode) pair;
                boolean isLiteral = assoc.value instanceof HashNode;
                duplicateCheck = isLiteral ? tru() : fals();

                if (hash == null) {                     // No hash yet. Define so order is preserved.
                    hash = copy(new Hash(args));
                    args = new ArrayList<>();           // Used args but we may find more after the splat so we reset
                } else if (!args.isEmpty()) {
                    addInstr(new RuntimeHelperCall(hash, MERGE_KWARGS, new Operand[] { hash, new Hash(args), duplicateCheck}));
                    args = new ArrayList<>();
                }
                Operand splat = buildWithOrder(assoc.value, hasAssignments);
                addInstr(new RuntimeHelperCall(hash, MERGE_KWARGS, new Operand[] { hash, splat, duplicateCheck}));
            }
        }

        if (hash == null) {           // non-**arg ordinary hash
            hash = copy(new Hash(args));
        } else if (!args.isEmpty()) { // ordinary hash values encountered after a **arg
            addInstr(new RuntimeHelperCall(hash, MERGE_KWARGS, new Operand[] { hash, new Hash(args), duplicateCheck}));
        }

        return hash;
    }

    private Operand buildIf(Variable result, IfNode node) {
        return buildConditional(result, node.predicate, node.statements, node.consequent);
    }

    private Operand buildIndexAndWrite(IndexAndWriteNode node) {
        return buildOpElementAsgnWith(node.receiver, node.arguments, node.block, node.value, fals());
    }

    private Operand buildIndexOperatorWrite(IndexOperatorWriteNode node) {
        return buildOpElementAsgnWithMethod(node.receiver, node.arguments, node.block, node.value, node.operator);
    }

    private Operand buildIndexOrWrite(IndexOrWriteNode node) {
        return buildOpElementAsgnWith(node.receiver, node.arguments, node.block, node.value, tru());
    }

    private Operand buildInstanceVariableRead(InstanceVariableReadNode node) {
        return buildInstVar(node.name);
    }

    private Operand buildInstanceVariableAndWrite(InstanceVariableAndWriteNode node) {
        return buildOpAsgnAnd(() -> addResultInstr(new GetFieldInstr(temp(), buildSelf(), node.name, false)),
                () -> buildInstAsgn(node.name, node.value));
    }

    private Operand buildInstanceVariableOrWrite(InstanceVariableOrWriteNode node) {
        return buildOpAsgnOr(() -> addResultInstr(new GetFieldInstr(temp(), buildSelf(), node.name, false)),
                () -> buildInstAsgn(node.name, node.value));
    }

    private Operand buildInstanceVariableWrite(InstanceVariableWriteNode node) {
        return buildInstAsgn(node.name, node.value);
    }

    private Operand buildInteger(IntegerNode node) {
        // FIXME: HAHAHAH horrible hack around integer being too much postprocessing.
        ByteList value = bytelistFrom(node);
        int base = node.isDecimal() ? 10 : node.isOctal() ? 8 : node.isHexadecimal() ? 16 : 2;
        int length = value.length();
        ByteList sanitizedValue = new ByteList(length);

        // Something wrong with str2inum not working due to '_'.  Work around for now.
        for (int i = 0; i < length; i++) {
            int c = value.get(i);
            if (c == '_') continue;
            sanitizedValue.append(c);
        }

        IRubyObject number = RubyNumeric.str2inum(getManager().runtime, getManager().getRuntime().newString(sanitizedValue), base, false, false);

        return number instanceof RubyBignum ?
                new Bignum(((RubyBignum) number).getBigIntegerValue()) :
                fix(RubyNumeric.fix2long(number));
    }

    private Operand buildInterpolatedMatchLastLine(Variable result, InterpolatedMatchLastLineNode node) {
        return buildDRegex(result, node.parts, calculateRegexpOptions(node.flags));
    }

    private Operand buildInterpolatedRegularExpression(Variable result, InterpolatedRegularExpressionNode node) {
        return buildDRegex(result, node.parts, calculateRegexpOptions(node.flags));
    }

    protected Operand buildDRegex(Variable result, Node[] children, RegexpOptions options) {
        Node[] pieces;
        if (children.length == 1) { // Case of interpolation only being an embedded element
            // We add an empty string here because the internal RubyRegexp.processDRegexp expects there
            // will be at least one string element which has had its encoding set properly by the options
            // value of the regexp.  Adding an empty string will pick up the encoding from options (this
            // empty string is how legacy parsers do this but it naturally falls out of the parser.
            pieces = new Node[children.length + 1];
            pieces[0] = new StringNode((short) 0, EMPTY.bytes(), 0, 0);
            pieces[1] = children[0];
        } else {
            pieces = children;
        }

        return super.buildDRegex(result, pieces, options);
    }

    private RegexpOptions calculateRegexpOptions(short flags) {
        RegexpOptions options = new RegexpOptions();
        options.setMultiline(RegularExpressionFlags.isMultiLine(flags));
        options.setIgnorecase(RegularExpressionFlags.isIgnoreCase(flags));
        options.setExtended(RegularExpressionFlags.isExtended(flags));
        // FIXME: Does this still exist in Ruby?
        //options.setFixed((joniOptions & RubyRegexp.RE_FIXED) != 0);
        options.setOnce(RegularExpressionFlags.isOnce(flags));
        if (RegularExpressionFlags.isAscii8bit(flags)) {
            options.setEncodingNone(RegularExpressionFlags.isAscii8bit(flags));
            options.setExplicitKCode(KCode.NONE);
        } else if (RegularExpressionFlags.isUtf8(flags)) {
            options.setExplicitKCode(KCode.UTF8);
            options.setFixed(true);
        } else if (RegularExpressionFlags.isEucJp(flags)) {
            options.setExplicitKCode(KCode.EUC);
            options.setFixed(true);
        } else if (RegularExpressionFlags.isWindows31j(flags)) {
            options.setExplicitKCode(KCode.SJIS);
            options.setFixed(true);
        }

        return options;
    }

    private Operand buildInterpolatedString(Variable result, InterpolatedStringNode node) {
        return buildDStr(result, node.parts, getEncoding(), false, getLine(node));
    }

    private Operand buildInterpolatedSymbol(Variable result, InterpolatedSymbolNode node) {
        return buildDSymbol(result, node.parts, getEncoding(), getLine(node));
    }

    private Operand buildInterpolatedXString(Variable result, InterpolatedXStringNode node) {
        return buildDXStr(result, node.parts, getEncoding(), getLine(node));
    }

    private Operand buildKeywordHash(KeywordHashNode node, int[] flags) {
        flags[0] |= CALL_KEYWORD;
        List<KeyValuePair<Operand, Operand>> args = new ArrayList<>();
        boolean hasAssignments = containsVariableAssignment(node);
        Variable hash = null;
        // Duplication checks happen when **{} are literals and not **h variable references.
        Operand duplicateCheck = fals();

        if (node.elements.length == 1 && node.elements[0] instanceof AssocSplatNode) { // Only a single rest arg here.  Do not bother to merge.
            flags[0] |= CALL_KEYWORD_REST;
            Operand splat = buildWithOrder(((AssocSplatNode) node.elements[0]).value, hasAssignments);

            return addResultInstr(new RuntimeHelperCall(temp(), HASH_CHECK, new Operand[] { splat }));
        }

        // pair is AssocNode or AssocSplatNode
        for (Node pair: node.elements) {
            Operand keyOperand;

            if (pair instanceof AssocNode) {
                keyOperand = build(((AssocNode) pair).key);
                args.add(new KeyValuePair<>(keyOperand, buildWithOrder(((AssocNode) pair).value, hasAssignments)));
            } else {  // AssocHashNode
                flags[0] |= CALL_KEYWORD_REST;
                AssocSplatNode assoc = (AssocSplatNode) pair;
                boolean isLiteral = assoc.value instanceof HashNode;
                duplicateCheck = isLiteral ? tru() : fals();

                if (hash == null) {                     // No hash yet. Define so order is preserved.
                    hash = copy(new Hash(args));
                    args = new ArrayList<>();           // Used args but we may find more after the splat so we reset
                } else if (!args.isEmpty()) {
                    addInstr(new RuntimeHelperCall(hash, MERGE_KWARGS, new Operand[] { hash, new Hash(args), duplicateCheck}));
                    args = new ArrayList<>();
                }
                Operand splat = buildWithOrder(assoc.value, hasAssignments);
                addInstr(new RuntimeHelperCall(hash, MERGE_KWARGS, new Operand[] { hash, splat, duplicateCheck}));
            }
        }

        if (hash == null) {           // non-**arg ordinary hash
            hash = copy(new Hash(args));
        } else if (!args.isEmpty()) { // ordinary hash values encountered after a **arg
            addInstr(new RuntimeHelperCall(hash, MERGE_KWARGS, new Operand[] { hash, new Hash(args), duplicateCheck}));
        }

        return hash;
    }

    private Operand buildLambda(LambdaNode node) {
        StaticScope staticScope = createStaticScopeFrom(node.locals, StaticScope.Type.BLOCK);
        Signature signature = calculateSignature(node.parameters);
        staticScope.setSignature(signature);
        return buildLambda(node.parameters, node.body, staticScope, signature, getLine(node));
    }

    private Operand buildLocalVariableRead(LocalVariableReadNode node) {
        return getLocalVariable(node.name, node.depth);
    }

    private Operand buildLocalAndVariableWrite(LocalVariableAndWriteNode node) {
        return buildOpAsgnAnd(() -> getLocalVariable(node.name, node.depth),
                () -> buildLocalVariableAssign(node.name, node.depth, node.value));
    }

    private Operand buildLocalOrVariableWrite(LocalVariableOrWriteNode node) {
        return buildOpAsgnOr(() -> getLocalVariable(node.name, node.depth),
                () -> buildLocalVariableAssign(node.name, node.depth, node.value));
    }

    private Operand buildLocalVariableWrite(LocalVariableWriteNode node) {
        return buildLocalVariableAssign(node.name, node.depth, node.value);
    }

    private Operand buildMatchLastLine(Variable result, MatchLastLineNode node) {
        return buildMatch(result,
                new Regexp(new ByteList(node.unescaped,
                        getRegexpEncoding(node.unescaped, node.flags)), calculateRegexpOptions(node.flags)));
    }

    private Operand buildMatchPredicate(MatchPredicateNode node) {
        Variable result = copy(tru());
        buildPatternMatch(result, copy(nil()), copy(nil()), node.pattern, build(node.value), false, true, copy(nil()));
        return result;
    }

    private Operand buildMatchRequired(MatchRequiredNode node) {
        return buildPatternCase(node.value, new Node[] { new InNode(node.pattern, null, 0, 0) }, null);
    }

    private Operand buildMatchWrite(Variable result, MatchWriteNode node) {
        Operand receiver = build(node.call.receiver);
        // FIXME: Can this ever be something more?
        Operand value = build(node.call.arguments.arguments[0]);

        if (result == null) result = temp();

        addInstr(new MatchInstr(scope, result, receiver, value));

        for (int i = 0; i < node.targets.length; i++) {
            LocalVariableTargetNode target = (LocalVariableTargetNode) node.targets[i];

            addInstr(new SetCapturedVarInstr(getLocalVariable(target.name, target.depth), result, target.name));
        }

        return result;
    }

    private Operand buildMissing(MissingNode node) {
        System.out.println("uh oh");
        return nil();
    }

    private Operand buildModule(ModuleNode node) {
        return buildModule(determineBaseName(node.constant_path), node.constant_path, node.body,
                createStaticScopeFrom(node.locals, StaticScope.Type.LOCAL),
                getLine(node), getEndLine(node));
    }

    private Operand buildMultiWriteNode(MultiWriteNode node) {
        Variable value = getValueInTemporaryVariable(build(node.value));
        Variable rhs = node.value instanceof ArrayNode ? value : addResultInstr(new ToAryInstr(temp(), value));

        buildMultiAssignment(node.lefts, node.rest, node.rights, rhs);

        return value;
    }

    // SplatNode, MultiWriteNode, LocalVariableWrite and lots of other normal writes
    private void buildMultiAssignment(Node[] pre, Node rest, Node[] post, Operand values) {
        final List<Tuple<Node, Variable>> assigns = new ArrayList<>();

        for (int i = 0; i < pre.length; i++) {
            assigns.add(new Tuple<>(pre[i], addResultInstr(new ReqdArgMultipleAsgnInstr(temp(), values, i))));
        }

        if (rest != null) {
            if (rest instanceof SplatNode) {
                Node realTarget = ((SplatNode) rest).expression;
                assigns.add(new Tuple<>(realTarget, addResultInstr(new RestArgMultipleAsgnInstr(temp(), values, 0, pre.length, post.length))));
            } else {
                assigns.add(new Tuple<>(null, addResultInstr(new RestArgMultipleAsgnInstr(temp(), values, 0, pre.length, post.length))));
            }
        }

        for (int j = 0; j < post.length; j++) {
            assigns.add(new Tuple<>(post[j], addResultInstr(new ReqdArgMultipleAsgnInstr(temp(), values, j, pre.length, post.length))));
        }

        for (Tuple<Node, Variable> assign: assigns) {
            buildAssignment(assign.a, assign.b);
        }
    }

    private Operand buildNext(NextNode node) {
        return buildNext(buildArgumentsAsArgument(node.arguments), getLine(node));
    }

    private Operand buildNumberedReferenceRead(NumberedReferenceReadNode node) {
        return buildNthRef(node.number);
    }

    private Operand buildOr(OrNode node) {
        return buildOr(build(node.left), () -> build(node.right), binaryType(node.left));
    }

    private void buildParameters(ParametersNode parameters) {
        if (parameters == null) {
            Variable keywords = addResultInstr(new ReceiveKeywordsInstr(temp(), false, false));
            buildArity(false, false, keywords, 0, -1, 0);
            return;
        }

        if (parameters.keyword_rest instanceof ForwardingParameterNode) {
            Variable keywords = addResultInstr(new ReceiveKeywordsInstr(temp(), true, true));
            receiveNonBlockArgs(parameters, keywords, true);
            RubySymbol restName = symbol(FWD_REST);
            RubySymbol kwrestName = symbol(FWD_KWREST);
            RubySymbol blockName = symbol(FWD_BLOCK);
            addInstr(new ReceiveRestArgInstr(argumentResult(restName), keywords, parameters.requireds.length, parameters.requireds.length));
            addInstr(new ReceiveKeywordRestArgInstr(argumentResult(kwrestName), keywords));
            Variable blockVar = argumentResult(blockName);
            Variable tmp = addResultInstr(new LoadImplicitClosureInstr(temp()));
            addInstr(new ReifyClosureInstr(blockVar, tmp));

            addArgumentDescription(ArgumentType.rest, restName);
            addArgumentDescription(ArgumentType.keyrest, kwrestName);
            addArgumentDescription(ArgumentType.block, blockName);

            return;
        }

        boolean hasRest = parameters.rest != null;
        boolean hasKeywords = parameters.keywords.length != 0 || parameters.keyword_rest != null;
        Variable keywords = addResultInstr(new ReceiveKeywordsInstr(temp(), hasRest, hasKeywords));

        // We want this to come in before arity check since arity will think no kwargs should exist.
        if (parameters.keyword_rest instanceof NoKeywordsParameterNode) {
            if_not(keywords, UndefinedValue.UNDEFINED, () -> addRaiseError("ArgumentError", "no keywords accepted"));
        }

        receiveNonBlockArgs(parameters, keywords, hasKeywords);

        if (hasKeywords) {
            int keywordsCount = parameters.keywords.length;
            Node[] kwArgs = parameters.keywords;
            for (int i = 0; i < keywordsCount; i++) {
                if (kwArgs[i] instanceof  OptionalKeywordParameterNode) {
                    buildKeywordParameter(keywords, ((OptionalKeywordParameterNode) kwArgs[i]).name,
                            ((OptionalKeywordParameterNode) kwArgs[i]).value);
                } else { // RequiredKeywordParameterNode
                    buildKeywordParameter(keywords, ((RequiredKeywordParameterNode) kwArgs[i]).name, null);
                }
            }
        }


        if (parameters.keyword_rest != null && parameters.keyword_rest instanceof KeywordRestParameterNode) {
            RubySymbol key = ((KeywordRestParameterNode) parameters.keyword_rest).name;
            ArgumentType type;
            if (key == null) {
                key = symbol(STAR_STAR);
                type = ArgumentType.anonkeyrest;
            } else {
                type = ArgumentType.keyrest;
            }

            addArgumentDescription(type, key);

            addInstr(new ReceiveKeywordRestArgInstr(getNewLocalVariable(key, 0), keywords));
        }

        receiveBlockArg(parameters.block);

        // FIXME: we calc signature earlier why do we do this again?  (for Prism and AST)
        int preCount = parameters.requireds.length;
        int optCount = parameters.optionals.length;
        int keywordsCount = parameters.keywords.length;
        int postCount = parameters.posts.length;
        int keyRest = parameters.keyword_rest == null ? -1 : preCount + optCount + postCount + keywordsCount;
        int requiredCount = preCount + postCount;

        buildArity(hasRest, hasKeywords, keywords, optCount, keyRest, requiredCount);
    }

    private void buildArity(boolean hasRest, boolean hasKeywords, Operand keywords, int optCount, int keyRest, int requiredCount) {
        // For closures, we don't need the check arity call
        if (scope instanceof IRMethod) {
            // Expensive to do this explicitly?  But, two advantages:
            // (a) on inlining, we'll be able to get rid of these checks in almost every case.
            // (b) compiler to bytecode will anyway generate this and this is explicit.
            // For now, we are going explicit instruction route.
            // But later, perhaps can make this implicit in the method setup preamble?

            addInstr(new CheckArityInstr(requiredCount, optCount, hasRest, keyRest, keywords));
        } else if (scope instanceof IRClosure && hasKeywords) {
            // FIXME: This is added to check for kwargs correctness but bypass regular correctness.
            // Any other arity checking currently happens within Java code somewhere (RubyProc.call?)
            addInstr(new CheckArityInstr(requiredCount, optCount, hasRest, keyRest, keywords));
        }
    }

    private void buildKeywordParameter(Variable keywords, RubySymbol key, Node value) {
        boolean isOptional = value != null;
        addKeyArgDesc(key, isOptional);

        label("kw_param_end", (end) -> {
            Variable av = getNewLocalVariable(key, 0);

            addInstr(new ReceiveKeywordArgInstr(av, keywords, key));
            addInstr(BNEInstr.create(end, av, UndefinedValue.UNDEFINED)); // if 'av' is not undefined, we are done

            if (isOptional) {
                // FIXME: I think this first nil out is not needed based on second copy
                addInstr(new CopyInstr(av, nil())); // wipe out undefined value with nil
                // FIXME: this is performing extra copy but something is generating a temp and not using local if we pass it to build
                copy(av, build(value));
            } else {
                addInstr(new RaiseRequiredKeywordArgumentError(key));
            }
        });
    }

    private Operand buildPostExecution(PostExecutionNode node) {
        return buildPostExe(node.statements, getLine(node));
    }

    private Operand buildPreExecution(PreExecutionNode node) {
        return buildPreExe(node.statements);
    }

    private Operand buildRange(RangeNode node) {
        return buildRange(node.left, node.right, node.isExcludeEnd());
    }

    private Operand buildRational(RationalNode node) {
        if (node.numeric instanceof FloatNode) {
            BigDecimal bd = new BigDecimal(bytelistFrom(node.numeric).toString());
            BigDecimal denominator = BigDecimal.ONE.scaleByPowerOfTen(bd.scale());
            BigDecimal numerator = bd.multiply(denominator);

            try {
                return new Rational(fix(numerator.longValueExact()), fix(denominator.longValueExact()));
            } catch (ArithmeticException ae) {
                return new Rational(new Bignum(numerator.toBigIntegerExact()), new Bignum(denominator.toBigIntegerExact()));
            }
        }
        // FIXME: Meh. will this always work.
        return new Rational((ImmutableLiteral) build(node.numeric), fix(1));
    }

    private Operand buildRedo(RedoNode node) {
        return buildRedo(getLine(node));
    }

    private Operand buildRegularExpression(RegularExpressionNode node) {
        return new Regexp(new ByteList(node.unescaped,
                getRegexpEncoding(node.unescaped, node.flags)), calculateRegexpOptions(node.flags));
    }

    private Encoding hackRegexpEncoding(byte[] data) {
        Encoding encoding = getEncoding();
        int length = data.length;
        int i = 0;

        while(i < length) {
            int len = encoding.length(data, i, length);
            if (len == -1) break;
            i += len;
        }

        if (i == length) {
            return encoding;
        }

        return ASCIIEncoding.INSTANCE;
    }

    private Operand buildRescueModifier(RescueModifierNode node) {
        return buildEnsureInternal(node.expression, null, null, node.rescue_expression, null, true, null, true, null);
    }

    private Operand buildRetry(RetryNode node) {
        return buildRetry(getLine(node));
    }

    private Operand buildReturn(ReturnNode node) {
        if (isTopLevel() && node.arguments != null) {
            scope.getManager().getRuntime().getWarnings().warn(getFileName(), getLine(node) + 1, "argument of top-level return is ignored");
        }

        Operand args = node.arguments == null ? nil() : buildYieldArgs(node.arguments.arguments, new int[1]);

        return buildReturn(args, getLine(node));
    }

    private Operand buildRestKeywordArgs(KeywordHashNode keywordArgs, int[] flags) {
        flags[0] |= CALL_KEYWORD_REST;
        Node[] pairs = keywordArgs.elements;
        boolean containsVariableAssignment = containsVariableAssignment(keywordArgs);

        if (pairs.length == 1) { // Only a single rest arg here.  Do not bother to merge.
            Operand splat = buildWithOrder(((AssocSplatNode) pairs[0]).value, containsVariableAssignment);

            return addResultInstr(new RuntimeHelperCall(temp(), HASH_CHECK, new Operand[] { splat }));
        }

        Variable splatValue = copy(new Hash(new ArrayList<>()));
        for (int i = 0; i < pairs.length; i++) {
            Operand splat = buildWithOrder(pairs[i], containsVariableAssignment);
            addInstr(new RuntimeHelperCall(splatValue, MERGE_KWARGS, new Operand[] { splatValue, splat, fals() }));
        }

        return splatValue;
    }

    private Operand buildSingletonClass(SingletonClassNode node) {
        return buildSClass(node.expression, node.body,
                createStaticScopeFrom(node.locals, StaticScope.Type.LOCAL), getLine(node), getEndLine(node));
    }

    private Operand buildSourceEncoding() {
        return buildEncoding(getEncoding());
    }

    private Operand buildSourceFile() {
        return new FrozenString(scope.getFile());
    }

    private Operand buildSourceLine(Node node) {
        return fix(getLine(node) + 1);
    }

    private Operand buildSplat(SplatNode node) {
        return buildSplat(build(node.expression));
    }

    private Operand buildSplat(Operand value) {
        return addResultInstr(new BuildSplatInstr(temp(), value, true));
    }

    private Operand buildString(StringNode node) {
        if (node.isFrozen()) {
            return new FrozenString(bytelistFrom(node), CR_UNKNOWN, scope.getFile(), getLine(node));
        } else {
            return copy(temp(), new MutableString(bytelistFrom(node), CR_UNKNOWN, scope.getFile(), getLine(node)));
        }
    }

    private Operand buildFrozenString(StringNode node) {
        return new FrozenString(bytelistFrom(node), CR_UNKNOWN, scope.getFile(), getLine(node));
    }

    private Operand buildSuper(Variable result, SuperNode node) {
        return buildSuper(result, node.block, node.arguments, getLine(node), node.hasNewLineFlag());
    }

    private Operand buildSymbol(SymbolNode node) {
        return new Symbol(symbol(node));
    }

    private Operand buildUndef(UndefNode node) {
        Operand last = nil();
        for (Operand name: buildNodeList(node.names)) {
            last = buildUndef(name);
        }
        return last;
    }

    private Operand buildUnless(Variable result, UnlessNode node) {
        return buildConditional(result, node.predicate, node.consequent, node.statements);
    }

    private Operand buildUntil(UntilNode node) {
        return buildConditionalLoop(node.predicate, node.statements, false, !node.isBeginModifier());
    }

    private void buildWhenSplatValues(Variable eqqResult, Node node, Operand testValue, Label bodyLabel,
                                      Set<IRubyObject> seenLiterals) {
        // FIXME: could see errors since this is missing whatever is YARP args{cat,push}?
        if (node instanceof StatementsNode) {
            buildWhenValues(eqqResult, ((StatementsNode) node).body, testValue, bodyLabel, seenLiterals);
        } else if (node instanceof SplatNode) {
            buildWhenValue(eqqResult, testValue, bodyLabel, node, seenLiterals, true);
        } else {
            buildWhenValue(eqqResult, testValue, bodyLabel, node, seenLiterals, true);
        }
    }

    private Operand buildWhile(WhileNode node) {
        return buildConditionalLoop(node.predicate, node.statements, true, !node.isBeginModifier());
    }

    // FIXME: implement
    @Override
    protected boolean alwaysFalse(Node node) {
        return false;
    }

    // FIXME: implement
    @Override
    protected boolean alwaysTrue(Node node) {
        return false;
    }

    @Override
    protected Node[] exceptionNodesFor(RescueNode node) {
        return node.exceptions;
    }

    @Override
    protected Node bodyFor(RescueNode node) {
        return node.statements;
    }

    @Override
    protected RescueNode optRescueFor(RescueNode node) {
        return node.consequent;
    }

    // FIXME: Implement
    @Override
    protected boolean isSideEffectFree(Node node) {
        return false;
    }

    // FIXME: Implement
    @Override
    protected boolean isErrorInfoGlobal(Node body) {
        return false;
    }

    protected int dynamicPiece(Operand[] pieces, int i, Node pieceNode, Encoding encoding) {
        Operand piece;

        // somewhat arbitrary minimum size for interpolated values
        int estimatedSize = 4;

        while (true) { // loop to unwrap EvStr

            // FIXME: missing EmbddedVariableNode.
            if (pieceNode instanceof StringNode) {
                // FIXME: This is largely a duplicate buildString.  It can be partially genericalized.
                ByteList data = encoding == null ?
                        bytelistFrom((StringNode) pieceNode) :
                        new ByteList(((StringNode) pieceNode).unescaped, encoding);

                if (((StringNode) pieceNode).isFrozen()) {
                    piece = new FrozenString(data, CR_UNKNOWN, scope.getFile(), getLine(pieceNode));
                } else {
                    piece = new MutableString(data, CR_UNKNOWN, scope.getFile(), getLine(pieceNode));
                }

                estimatedSize += data.realSize();
            } else if (pieceNode instanceof EmbeddedStatementsNode) {
                if (scope.maybeUsingRefinements()) {
                    // refined asString must still go through dispatch
                    Variable result = temp();
                    addInstr(new AsStringInstr(scope, result, build(((EmbeddedStatementsNode) pieceNode).statements), scope.maybeUsingRefinements()));
                    piece = result;
                } else {
                    // evstr/asstring logic lives in BuildCompoundString now, unwrap and try again
                    pieceNode = ((EmbeddedStatementsNode) pieceNode).statements;
                    continue;
                }
            } else {
                piece = build(pieceNode);
            }

            break;
        }

        if (piece instanceof MutableString) {
            piece = ((MutableString)piece).frozenString;
        }

        pieces[i] = piece == null ? nil() : piece;

        return estimatedSize;
    }

    /**
     * Reify the implicit incoming block into a full Proc, for use as "block arg", but only if
     * a block arg is specified in this scope's arguments.
     *  @param node the arguments containing the block arg, if any
     *
     */
    protected void receiveBlockArg(Node node) {
        BlockParameterNode blockArg = (BlockParameterNode) node;

        // reify to Proc if we have a block arg
        if (blockArg != null) {
            // FIXME: Handle bare '&' case?
            RubySymbol name = blockArg.name == null ? symbol(FWD_BLOCK) : blockArg.name;
            Variable blockVar = argumentResult(name);
            addArgumentDescription(ArgumentType.block, name);
            Variable tmp = temp();
            addInstr(new LoadImplicitClosureInstr(tmp));
            addInstr(new ReifyClosureInstr(blockVar, tmp));
        }
    }

    protected void receiveNonBlockArgs(ParametersNode args, Variable keywords, boolean hasKeywords) {
        int preCount = args.requireds.length;
        int optCount = args.optionals.length;
        boolean hasRest = args.rest != null;
        int postCount = args.posts.length;
        int requiredCount = preCount + postCount;

        // Other args begin at index 0
        int argIndex = 0;

        // Pre(-opt and rest) required args
        Node[] pres = args.requireds;
        for (int i = 0; i < preCount; i++, argIndex++) {
            receivePreArg(pres[i], keywords, argIndex);
        }

        // Fixup opt/rest
        Node[] opts = args.optionals;

        // Now for opt args
        if (optCount > 0) {
            for (int j = 0; j < optCount; j++, argIndex++) {
                // We fall through or jump to variableAssigned once we know we have a valid value in place.
                Label variableAssigned = getNewLabel();
                OptionalParameterNode optArg = (OptionalParameterNode) opts[j];
                RubySymbol argName = optArg.name;
                Variable argVar = argumentResult(argName);
                addArgumentDescription(ArgumentType.opt, argName);
                // You need at least required+j+1 incoming args for this opt arg to get an arg at all
                addInstr(new ReceiveOptArgInstr(argVar, keywords, j, requiredCount, preCount));
                addInstr(BNEInstr.create(variableAssigned, argVar, UndefinedValue.UNDEFINED));
                // We add this extra nil copy because we do not know if we have a circular defininition of
                // argVar: proc { |a=a| } or proc { |a = foo(bar(a))| }.
                copy(argVar, nil());
                // This bare build looks weird but OptArgNode is just a marker and value is either a LAsgnNode
                // or a DAsgnNode.  So building the value will end up having a copy(var, assignment).
                copy(argVar, build(optArg.value));
                addInstr(new LabelInstr(variableAssigned));
            }
        }

        if (hasRest) {
            // Consider: def foo(*); .. ; end
            // For this code, there is no argument name available from the ruby code.
            // So, we generate an implicit arg name
            RubySymbol argName;

            if (args.rest instanceof RestParameterNode) {
                RestParameterNode restArg = (RestParameterNode) args.rest;
                    // FIXME: how do we annotate generated AST types to have isAnonymous etc...
                if (restArg.name == null) {
                    argName = symbol("*");
                    addArgumentDescription(ArgumentType.anonrest, argName);
                } else {
                    argName = restArg.name;
                    addArgumentDescription(ArgumentType.rest, argName);
                }
            } else { // ImplicitRestNode  (*,)
                argName = null;
            }

            // You need at least required+opt+1 incoming args for the rest arg to get any args at all
            // If it is going to get something, then it should ignore required+opt args from the beginning
            // because they have been accounted for already.
            addInstr(new ReceiveRestArgInstr(argumentResult(argName), keywords, argIndex, requiredCount + optCount));
        }

        // Post(-opt and rest) required args
        Node[] posts = args.posts;
        for (int i = 0; i < postCount; i++) {
            receivePostArg(posts[i], keywords, i, preCount, optCount, hasRest, postCount);
        }
    }

    // FIXME: I dislike both methods and procs use the same method.
    public void receivePreArg(Node node, Variable keywords, int argIndex) {
        if (node instanceof RequiredParameterNode) { // methods
            RubySymbol name = ((RequiredParameterNode) node).name;

            addArgumentDescription(ArgumentType.req, name);

            addInstr(new ReceivePreReqdArgInstr(argumentResult(name), keywords, argIndex));
        } else if (node instanceof MultiTargetNode) { // methods
            Variable v = temp();
            addInstr(new ReceivePreReqdArgInstr(v, keywords, argIndex));
            addArgumentDescription(ArgumentType.anonreq, null);
            Variable rhs = addResultInstr(new ToAryInstr(temp(), v));
            buildMultiAssignment(((MultiTargetNode) node).lefts, ((MultiTargetNode) node).rest, ((MultiTargetNode) node).rights, rhs);
        } else if (node instanceof ClassVariableTargetNode) {  // blocks/for
            Variable v = temp();
            addInstr(new ReceivePreReqdArgInstr(v, keywords, argIndex));
            addInstr(new PutClassVariableInstr(classVarDefinitionContainer(), ((ClassVariableTargetNode) node).name, v));
        } else if (node instanceof ConstantTargetNode) {  // blocks/for
            Variable v = temp();
            addInstr(new ReceivePreReqdArgInstr(v, keywords, argIndex));
            putConstant(((ConstantTargetNode) node).name, v);
        } else if (node instanceof InstanceVariableTargetNode) {  // blocks/for
            Variable v = temp();
            addInstr(new ReceivePreReqdArgInstr(v, keywords, argIndex));
            addInstr(new PutFieldInstr(buildSelf(), ((InstanceVariableTargetNode) node).name, v));
        } else if (node instanceof LocalVariableTargetNode) {  // blocks/for
            Variable v = getLocalVariable(((LocalVariableTargetNode) node).name, ((LocalVariableTargetNode) node).depth);
            addInstr(new ReceivePreReqdArgInstr(v, keywords, argIndex));
        } else {
            throw notCompilable("Can't build required parameter node", node);
        }
    }

    public void receivePostArg(Node node, Variable keywords, int argIndex, int preCount, int optCount, boolean hasRest, int postCount) {
        if (node instanceof RequiredParameterNode) {
            RubySymbol argName = ((RequiredParameterNode) node).name;

            addArgumentDescription(ArgumentType.req, argName);

            addInstr(new ReceivePostReqdArgInstr(argumentResult(argName), keywords, argIndex, preCount, optCount, hasRest, postCount));
        } else if (node instanceof MultiTargetNode) {
            Variable v = temp();
            addInstr(new ReceivePostReqdArgInstr(v, keywords, argIndex, preCount, optCount, hasRest, postCount));

            addArgumentDescription(ArgumentType.anonreq, null);

            Variable tmp = temp();
            addInstr(new ToAryInstr(tmp, v));

            buildMultiAssignment(((MultiTargetNode) node).lefts, ((MultiTargetNode) node).rest, ((MultiTargetNode) node).rights, tmp);
        } else {
            throw notCompilable("Can't build required parameter node", node);
        }
    }

    private void addKeyArgDesc(RubySymbol key, boolean isOptional) {
        addArgumentDescription(isOptional ? ArgumentType.key : ArgumentType.keyreq, key);
    }

    private Operand buildProgram(ProgramNode node) {
        return build(node.statements);
    }

    private Operand buildStatements(StatementsNode node) {
        if (node == null) return nil();

        Operand result = null;

        for (Node child : node.body) {
            result = build(child);
        }

        return result == null ? nil() : result;
    }

    @Override
    protected void buildWhenArgs(WhenNode whenNode, Operand testValue, Label bodyLabel, Set<IRubyObject> seenLiterals) {
        Variable eqqResult = temp();
        Node[] exprNodes = whenNode.conditions;

        if (exprNodes.length == 1) {
            if (exprNodes[0] instanceof SplatNode) {
                buildWhenSplatValues(eqqResult, exprNodes[0], testValue, bodyLabel, seenLiterals);
            } else {
                buildWhenValue(eqqResult, testValue, bodyLabel, exprNodes[0], seenLiterals, false);
            }
        } else {
            for (Node value: exprNodes) {
                buildWhenValue(eqqResult, testValue, bodyLabel, value, seenLiterals, value instanceof SplatNode);
            }
        }
    }

    private Operand buildXString(Variable result, XStringNode node) {
        ByteList value = new ByteList(node.unescaped, getEncoding());
        int codeRange = StringSupport.codeRangeScan(value.getEncoding(), value);
        return fcall(result, buildSelf(), "`", new FrozenString(value, codeRange, scope.getFile(), getLine(node)));
    }

    Operand buildYield(Variable aResult, YieldNode node) {
        if (aResult == null) aResult = temp();

        IRScope hardScope = scope.getNearestNonClosurelikeScope();
        if (hardScope instanceof IRScriptBody || hardScope instanceof IRModuleBody) throwSyntaxError(getLine(node), "Invalid yield");

        Variable result = aResult;
        int[] flags = new int[]{0};
        boolean unwrap = true;

        if (node.arguments != null) {
            if (node.arguments.arguments != null &&
                    node.arguments.arguments.length == 1 &&
                    !(node.arguments.arguments[0] instanceof KeywordHashNode) &&
            !(node.arguments.arguments[0] instanceof SplatNode)) {
                unwrap = false;
            }
        }
        Operand value = buildYieldArgs(node.arguments != null ? node.arguments.arguments : null, flags);
        addInstr(new YieldInstr(result, getYieldClosureVariable(), value, flags[0], unwrap));
        return result;
    }

    Operand buildYieldArgs(Node[] args, int[] flags) {
        if (args == null) {
            return UndefinedValue.UNDEFINED;
        } else if (args.length == 1) {
            if (args[0] instanceof SplatNode) { // yield *c
                flags[0] |= CALL_SPLATS;
                return new Splat(buildSplat((SplatNode) args[0]));
            } else if (args[0] instanceof KeywordHashNode) {
                return buildKeywordHash((KeywordHashNode) args[0], flags);
            } else { // ???
                return build(args[0]);
            }
        } else {
            int lastSplat = -1;
            Operand[] operands = new Operand[args.length];
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof SplatNode) { // yield with a splat in it in any position
                    if (lastSplat != -1) { // already one splat encountered
                        operands[i] = twoArgs(operands, lastSplat, i, buildSplat((SplatNode) args[i]), flags);
                    } else { // first and possibly only splat
                        flags[0] |= CALL_SPLATS; // FIXME: not sure all splats count?
                        if (i == 0) {
                            operands[i] = buildSplat((SplatNode) args[i]);
                        } else {
                            operands[i] = catArgs(operands, 0, i, buildSplat((SplatNode) args[i]), flags);
                        }
                    }
                    lastSplat = i;
                } else if (args[i] instanceof KeywordHashNode) {
                    operands[i] = buildKeywordHash((KeywordHashNode) args[i], flags);
                } else {
                    operands[i] = build(args[i]);
                }
            }

            if (lastSplat != -1) {
                if (lastSplat == args.length - 1) return operands[lastSplat];

                return pushArgs(operands, lastSplat, args.length, flags); // Some trailing elements after a splat
            } else {
                return new Array(operands);
            }
        }
    }

    private Operand twoArgs(Operand[] operands, int lastSplat, int newSplat, Operand newSplatValue, int[] flags) {
        Operand lhs = pushArgs(operands, lastSplat, newSplat, flags);
        return addResultInstr(new BuildCompoundArrayInstr(temp(), lhs, newSplatValue, false, (flags[0] & CALL_KEYWORD_REST) != 0));
    }

    private Operand pushArgs(Operand[] operands, int lastSplat, int newSplat, int[] flags) {
        int length = newSplat - lastSplat - 1;
        if (length == 0) return operands[lastSplat];

        Operand rhs;
        if (length == 1 && (flags[0] & CALL_KEYWORD_REST) != 0) {
            rhs = operands[lastSplat + 1];
        } else {
            rhs = subArray(operands, lastSplat + 1, length);
        }
        return addResultInstr(new BuildCompoundArrayInstr(temp(), operands[lastSplat], rhs, (flags[0] & CALL_KEYWORD_REST) != 0, (flags[0] & CALL_KEYWORD_REST) != 0));
    }

    private Operand catArgs(Operand[] args, int start, int length, Operand splat, int[] flags) {
        Array lhs = subArray(args, start, length);
        return addResultInstr(new BuildCompoundArrayInstr(temp(), lhs, splat, false, (flags[0] & CALL_KEYWORD_REST) != 0));
    }

    private static Array subArray(Operand[] args, int start, int length) {
        Operand[] elts = new Operand[length];
        System.arraycopy(args, start, elts, 0, length);
        Array lhs = new Array(elts);
        return lhs;
    }

    // Assumption: @, @@, $ assignments are all accessed directly through instructions
    // so they will always be indirected through temporary variables.  LocalVariables
    // are just operands so we only need to be aware of these being used within assignments.
    @Override
    protected boolean containsVariableAssignment(Node node) {
        if (node instanceof LocalVariableWriteNode ||
                node instanceof LocalVariableOperatorWriteNode ||
                node instanceof LocalVariableAndWriteNode ||
                node instanceof LocalVariableOrWriteNode ||
                node instanceof InstanceVariableWriteNode ||
                node instanceof InstanceVariableOperatorWriteNode ||
                node instanceof InstanceVariableAndWriteNode ||
                node instanceof InstanceVariableOrWriteNode ||
                node instanceof GlobalVariableWriteNode ||
                node instanceof GlobalVariableOperatorWriteNode ||
                node instanceof GlobalVariableAndWriteNode ||
                node instanceof GlobalVariableOrWriteNode ||
                node instanceof ClassVariableWriteNode ||
                node instanceof ClassVariableOperatorWriteNode ||
                node instanceof ClassVariableAndWriteNode ||
                node instanceof ClassVariableOrWriteNode) {
            return true;
        }
        return false;
    }

    // FIXME: needs to derive this walking down tree.
    boolean containsVariableAssignment(Node[] nodes) {
        for (int i = 0; i < nodes.length; i++) {
            if (containsVariableAssignment(nodes[i])) return true;
        }
        return false;
    }

    @Override
    protected Operand frozen_string(Node node) {
        // FIXME: this + isStringLiteral might need to change.
        return buildString((StringNode) node);
    }

    @Override
    protected Operand getContainerFromCPath(Node node) {

        if (node instanceof ConstantReadNode) {
            return findContainerModule();
        } else if (node instanceof ConstantPathNode) {
            ConstantPathNode path = (ConstantPathNode) node;

            if (path.parent == null) { // ::Foo
                return getManager().getObjectClass();
            } else {
                return build(path.parent);
            }
        }
        // FIXME: We may not need these based on whether there are more possible nodes.
        throw notCompilable("Unsupported node in module path", node);
    }

    @Override
    protected Variable buildPatternEach(Label testEnd, Variable result, Operand original, Variable deconstructed, Operand value, Node exprNodes, boolean inAlternation, boolean isSinglePattern, Variable errorString) {
        if (exprNodes instanceof StatementsNode && ((StatementsNode) exprNodes).body.length == 1) {
            buildPatternEach(testEnd, result, original, deconstructed, value, ((StatementsNode) exprNodes).body[0], inAlternation, isSinglePattern, errorString);
        } else if (exprNodes instanceof ArrayPatternNode) {
            ArrayPatternNode node = (ArrayPatternNode) exprNodes;
            buildArrayPattern(testEnd, result, deconstructed, node.constant, node.requireds, node.rest, node.posts, value, inAlternation, isSinglePattern, errorString);
        } else if (exprNodes instanceof CapturePatternNode) {
            buildPatternEach(testEnd, result, original, deconstructed, value, ((CapturePatternNode) exprNodes).value, inAlternation, isSinglePattern, errorString);
            buildAssignment(((CapturePatternNode) exprNodes).target, value);
        } else if (exprNodes instanceof HashPatternNode) {
            HashPatternNode node = (HashPatternNode) exprNodes;
            Node[] keys = getKeys(node);
            Node rest = node.rest != null ? node.rest : getRestFromKeys(node);
            buildHashPattern(testEnd, result, deconstructed, node.constant, node, keys, rest, value, inAlternation, isSinglePattern, errorString);
        } else if (exprNodes instanceof FindPatternNode) {
            getManager().getRuntime().getWarnings().warnExperimental(getFileName(), getLine(exprNodes) + 1,
                    "Find pattern is experimental, and the behavior may change in future versions of Ruby!");
            FindPatternNode node = (FindPatternNode) exprNodes;
            buildFindPattern(testEnd, result, deconstructed, node.constant, node.left, node.requireds, node.right, value, inAlternation, isSinglePattern, errorString);
        } else if (exprNodes instanceof IfNode) {
            IfNode node = (IfNode) exprNodes;
            buildPatternEachIf(result, original, deconstructed, value, node.predicate, node.statements, node.consequent, inAlternation, isSinglePattern, errorString);
        } else if (exprNodes instanceof UnlessNode) {
            UnlessNode node = (UnlessNode) exprNodes;
            buildPatternEachIf(result, original, deconstructed, value, node.predicate, node.consequent, node.statements, inAlternation, isSinglePattern, errorString);
        } else if (exprNodes instanceof LocalVariableTargetNode) {
            buildPatternLocal((LocalVariableTargetNode) exprNodes, value, inAlternation);
        } else if (exprNodes instanceof ImplicitNode) {
            buildPatternEach(testEnd, result, original, deconstructed, value, ((ImplicitNode) exprNodes).value, inAlternation, isSinglePattern, errorString);
        } else if (exprNodes instanceof AssocSplatNode) {
            AssocSplatNode node = (AssocSplatNode) exprNodes;

            buildPatternLocal((LocalVariableTargetNode) node.value, value, inAlternation);
        } else if (exprNodes instanceof ImplicitRestNode) {
            // do nothing
        } else if (exprNodes instanceof SplatNode) {
            buildAssignment(((SplatNode) exprNodes).expression, value);
            // do nothing
        } else if (exprNodes instanceof AlternationPatternNode) {
            buildPatternOr(testEnd, original, result, deconstructed, value, ((AlternationPatternNode) exprNodes).left,
                    ((AlternationPatternNode) exprNodes).right, isSinglePattern, errorString);
        } else {
            Operand expression = build(exprNodes);
            boolean needsSplat = exprNodes instanceof AssocSplatNode; // FIXME: just a guess this is all we need for splat?

            addInstr(new EQQInstr(scope, result, expression, value, needsSplat, scope.maybeUsingRefinements()));
        }

        return result;
    }

    private Encoding getRegexpEncoding(byte[] bytes, short flags) {
        Encoding encoding = getRegexpEncodingFromOptions(flags);

        return encoding == null ? hackRegexpEncoding(bytes) : encoding;
    }

    private Encoding getRegexpEncodingFromOptions(short flags) {
        return RegularExpressionFlags.isAscii8bit(flags) ? ASCIIEncoding.INSTANCE :
                RegularExpressionFlags.isUtf8(flags) ? UTF8Encoding.INSTANCE :
                        RegularExpressionFlags.isEucJp(flags) ? EUCJPEncoding.INSTANCE :
                                RegularExpressionFlags.isWindows31j(flags) ? Windows_31JEncoding.INSTANCE :
                                        null;
    }

    private Node getRestFromKeys(HashPatternNode node) {
        int length = node.elements.length;

        // FIXME: can there be multiple assocsplat and why isn't this rest in HashPatternNode
        for (int i = 0; i < length; i++) {
            if (node.elements[i] instanceof AssocSplatNode) return node.elements[i];
        }

        return null;
    }

    private Node[] getKeys(HashPatternNode node) {
        int length = node.elements.length;
        Node[] keys = new Node[length];
        int i = 0;

        for (; i < length; i++) {
            if (node.elements[i] instanceof AssocNode) {
                AssocNode assoc = (AssocNode) node.elements[i];

                keys[i] = assoc.key;
            } else {
                break; // rest kwarg at end we should ignore.
            }
        }

        if (i != length) {
            Node[] newKeys = new Node[i];
            System.arraycopy(keys, 0, newKeys, 0, i);
            return newKeys;
        }

        return keys;
    }

    Operand buildPatternLocal(LocalVariableTargetNode node, Operand value, boolean inAlternation) {
        return buildPatternLocal(value, node.name, getLine(node), node.depth, inAlternation);
    }

    @Override
    protected void buildAssocs(Label testEnd, Operand original, Variable result, HashPatternNode assocs, boolean inAlteration, boolean isSinglePattern, Variable errorString, boolean hasRest, Variable d) {

        for (Node node: assocs.elements) {
            // FIXME: There can be more than  AssocNode in here.
            if (node instanceof AssocNode) {
                // FIXME: only build literals (which are guaranteed to build without raising).
                Operand key = build(((AssocNode) node).key);
                call(result, d, "key?", key);
                cond_ne(testEnd, result, tru());

                String method = hasRest ? "delete" : "[]";
                Operand value = call(temp(), d, method, key);
                Node valueNode =  ((AssocNode) node).value;
                if (valueNode == null) {
                    Node keyHack = ((AssocNode) node).key;
                    RubySymbol name = null;
                    if (keyHack instanceof SymbolNode) {
                        name = symbol((SymbolNode) keyHack);
                    } else {
                        throwSyntaxError(getLine(node), "what else is in this");
                    }
                    // FIXME: This needs depth.
                    buildPatternLocal(value, name, getLine(node), 0, inAlteration);
                } else {
                    buildPatternEach(testEnd, result, original, copy(nil()), value, ((AssocNode) node).value, inAlteration, isSinglePattern, errorString);
                }

                cond_ne(testEnd, result, tru());
            }
        }
    }

    @Override
    protected boolean isNilRest(Node rest) {
        return rest instanceof NoKeywordsParameterNode;
    }

    @Override
    protected Node getInExpression(Node node) {
        return ((InNode) node).pattern;
    }

    @Override
    protected Node getInBody(Node node) {
        return ((InNode) node).statements;
    }

    @Override
    protected boolean isBareStar(Node node) {
        return node instanceof AssocSplatNode && ((AssocSplatNode) node).value == null;
    }

    private int getEndLine(Node node) {
        int endOffset = node.endOffset();
        // FIXME: Seems like either newline visitor or prism is sometimes reporting the offset one past last indexable source location (fairly rarely).
        if (endOffset >= nodeSource.bytes.length) endOffset = nodeSource.bytes.length - 1;
        return nodeSource.line(endOffset) - 1;
    }

    @Override
    protected int getLine(Node node) {
        int line = nodeSource.line(node.startOffset);
        //System.out.println("LINE(0): " + line);
        // internals expect 0-based value.
        return line - 1;
    }

    @Override
    public LocalVariable getLocalVariable(RubySymbol name, int scopeDepth) {
        // FIXME: Huge heap of weirdness...scope adds for loop depth per for loop found.  call another method to
        // remove that depth so it can be added back.
        int depth = scope.correctVariableDepthForForLoopsForEncoding(scopeDepth);
        return scope.getLocalVariable(name, depth);
    }

    @Override
    protected IRubyObject getWhenLiteral(Node node) {
        Ruby runtime = scope.getManager().getRuntime();

        if (node instanceof IntegerNode) {
            // FIXME: determine fixnum/bignum
            return null;
        } else if (node instanceof FloatNode) {
            return null;
            // FIXME:
            //return runtime.newFloat((((FloatNode) node)));
        } else if (node instanceof ImaginaryNode) {
            return null;
            // FIXME:
            //return RubyComplex.newComplexRaw(runtime, getWhenLiteral(((ComplexNode) node).getNumber()));
        } else if (node instanceof RationalNode) {
            return null;
            // FIXME:
            /*
            return RubyRational.newRationalRaw(runtime,
                    getWhenLiteral(((RationalNode) node).getDenominator()),
                    getWhenLiteral(((RationalNode) node).getNumerator()));*/
        } else if (node instanceof NilNode) {
            return runtime.getNil();
        } else if (node instanceof TrueNode) {
            return runtime.getTrue();
        } else if (node instanceof FalseNode) {
            return runtime.getFalse();
        } else if (node instanceof SymbolNode) {
            return symbol((SymbolNode) node);
        } else if (node instanceof StringNode) {
            return runtime.newString((bytelistFrom((StringNode) node)));
        }

        return null;
    }

    @Override
    protected boolean isLiteralString(Node node) {
        return node instanceof StringNode;
    }

    // FIXME: This only seems to be used in opelasgnor on the first element (lhs) but it is very unclear what is possible here.
    @Override
    protected boolean needsDefinitionCheck(Node node) {
        return !(node instanceof ClassVariableWriteNode ||
                node instanceof ConstantPathWriteNode ||
                node instanceof LocalVariableWriteNode ||
                node instanceof LocalVariableReadNode ||
                node instanceof FalseNode ||
                node instanceof GlobalVariableWriteNode ||
                node instanceof MatchRequiredNode ||
                node instanceof MatchPredicateNode ||
                node instanceof NilNode ||
                //node instanceof OperatorAssignmentNode ||
                node instanceof SelfNode ||
                node instanceof TrueNode);
    }

    @Override
    protected void receiveMethodArgs(DefNode defNode) {
        buildParameters(defNode.parameters);
    }

    @Override
    protected Operand setupCallClosure(Node args, Node block) {
        if (block == null) {
            if (args != null && isForwardingArguments(args)) {
                return getLocalVariable(symbol(FWD_BLOCK), scope.getStaticScope().isDefined("&"));
            }
            return NullBlock.INSTANCE;
        }

        if (block instanceof BlockNode) {
            return build(block);
        } else if (block instanceof BlockArgumentNode) {
            return buildBlockArgument((BlockArgumentNode) block);
        }

        throw notCompilable("Encountered unexpected block node", block);
    }

    private boolean isForwardingArguments(Node args) {
        for (Node child: ((ArgumentsNode) args).arguments) {
            if (child instanceof ForwardingArgumentsNode) return true;
        }
        return false;
    }

    private ByteList bytelistFrom(StringNode node) {
        Encoding encoding = node.isForcedBinaryEncoding() ?
                ASCIIEncoding.INSTANCE :
                node.isForcedUtf8Encoding() ?
                        UTF8Encoding.INSTANCE :
                        getEncoding();

        return new ByteList(node.unescaped, encoding);
    }

    private ByteList bytelistFrom(Node node) {
        return new ByteList(source, node.startOffset, node.length);
    }

    public static Signature calculateSignature(ParametersNode parameters) {
        if (parameters == null) return Signature.NO_ARGUMENTS;

        int pre = parameters.requireds.length;
        int opt = parameters.optionals.length;
        int post = parameters.posts.length;
        int keywords = parameters.keywords.length;
        // FIXME: this needs more than norm
        Signature.Rest rest = parameters.rest == null ? Signature.Rest.NONE :
                parameters.rest instanceof ImplicitRestNode ? Signature.Rest.ANON : Signature.Rest.NORM;

        int requiredKeywords = 0;
        if (keywords > 0) {
            for (int i = 0; i < parameters.keywords.length; i++) {
                if (parameters.keywords[i] instanceof RequiredKeywordParameterNode) requiredKeywords++;
            }
        }
        int keywordRestIndex = parameters.keyword_rest == null ? -1 : pre + opt + post + keywords;
        return Signature.from(pre, opt, post, keywords, requiredKeywords, rest, keywordRestIndex);
    }

    private Signature calculateSignature(Node parameters) {
        if (parameters == null) return Signature.NO_ARGUMENTS;

        if (parameters instanceof BlockParametersNode) {
            return calculateSignature(((BlockParametersNode) parameters).parameters);
        } else if (parameters instanceof NumberedParametersNode) {
            return Signature.from(((NumberedParametersNode) parameters).maximum, 0, 0, 0, 0, Signature.Rest.NONE, -1);
        }

        throw notCompilable("Unknown signature for block parameters", parameters);
    }

    private Signature calculateSignatureFor(Node node) {
        if (node instanceof MultiTargetNode) {
            MultiTargetNode multi = (MultiTargetNode) node;
            Signature.Rest rest = multi.rest != null ? Signature.Rest.NORM : Signature.Rest.NONE;
            return Signature.from(multi.lefts.length, 0, multi.rights.length, 0, 0, rest, -1);
        } else {
            return Signature.from(1, 0, 0, 0, 0, Signature.Rest.NONE, -1);
        }
    }

    public StaticScope createStaticScopeFrom(RubySymbol[] tokens, StaticScope.Type type) {
        return createStaticScopeFrom(staticScope.getFile(), tokens, type, staticScope);
    }

    public static StaticScope createStaticScopeFrom(String fileName, RubySymbol[] tokens, StaticScope.Type type, StaticScope parent) {
        String[] strings = new String[tokens.length];
        // FIXME: this should be iso_8859_1 strings and not default charset.
        for(int i = 0; i < tokens.length; i++) {
            strings[i] = tokens[i].idString();
        }

        // FIXME: keywordArgIndex?
        return StaticScopeFactory.newStaticScope(parent, type, fileName, strings, -1);
    }

    private ByteList determineBaseName(Node node) {
        if (node instanceof ConstantReadNode) {
            return ((ConstantReadNode) node).name.getBytes();
        } else if (node instanceof ConstantPathNode) {
            return determineBaseName(((ConstantPathNode) node).child);
        }
        throw notCompilable("Unsupported node in module path", node);
    }

    private CallType determineCallType(Node node) {
        return node == null || node instanceof SelfNode ? CallType.FUNCTIONAL : CallType.NORMAL;
    }

    // FIXME: need to know about breaks
    protected boolean canBeLazyMethod(DefNode node) {
        return true;
    }

    // FIXME: We need abstraction for getting names from nodes.
    private RubySymbol globalVariableName(Node node) {
        if (node instanceof GlobalVariableReadNode) return ((GlobalVariableReadNode) node).name;
        if (node instanceof BackReferenceReadNode) return ((BackReferenceReadNode) node).name;
        if (node instanceof NumberedReferenceReadNode) return symbol("$" + ((NumberedReferenceReadNode) node).number);

        throw notCompilable("Unknown global variable type", node);
    }

    private NotCompilableException notCompilable(String message, Node node) {
        int line = scope.getLine() + 1;
        String loc = scope.getFile() + ":" + line;
        String what = node != null ? node.getClass().getSimpleName() + " - " + loc : loc;
        return new NotCompilableException(message + " (" + what + ").");
    }

    // All splats on array construction will stuff them into a hash.
    private boolean hasOnlyRestKwargs(Node[] elements) {
        for (Node element: elements) {
            if (!(element instanceof AssocSplatNode)) return false;
        }

        return true;
    }
    @Override
    protected Operand putConstant(ConstantPathNode path, Operand value) {
        return putConstant(buildModuleParent(path.parent), ((ConstantReadNode) path.child).name, value);
    }

    protected RubySymbol symbol(SymbolNode node) {
        short flags = node.flags;
        Encoding encoding = SymbolFlags.isForcedUsAsciiEncoding(flags) ? USASCIIEncoding.INSTANCE :
                SymbolFlags.isForcedUtf8Encoding(flags) ? UTF8Encoding.INSTANCE :
                        SymbolFlags.isForcedBinaryEncoding(flags) ? ASCIIEncoding.INSTANCE :
                                getEncoding();
        ByteList bytelist = new ByteList(node.unescaped, encoding);

        // FIXME: This should be done by prism.
        if (RubyString.scanForCodeRange(bytelist) == StringSupport.CR_BROKEN) {
            Ruby runtime = getManager().getRuntime();
            throw runtime.newSyntaxError(str(runtime, "invalid symbol in encoding " + getEncoding() + " :\"", inspectIdentifierByteList(runtime, bytelist), "\""));
        }

        return symbol(bytelist);
    }

    @Override
    protected Node referenceFor(RescueNode node) {
        return node.reference;
    }

    @Override
    protected Node whenBody(WhenNode arm) {
        return arm.statements;
    }

    protected Operand buildOpAsgnOrWithDefined(Node definitionNode, VoidCodeBlockOne getter, CodeBlock setter) {
        Label existsDone = getNewLabel();
        Label done = getNewLabel();
        Operand def = buildGetDefinition2(definitionNode);
        Variable result = def instanceof Variable ? (Variable) def : copy(temp(), def);
        addInstr(createBranch(result, nil(), existsDone));
        getter.run(result);
        addInstr(new LabelInstr(existsDone));
        addInstr(createBranch(result, getManager().getTrue(), done));
        Operand value = setter.run();
        copy(result, value);
        addInstr(new LabelInstr(done));

        return result;
    }
}