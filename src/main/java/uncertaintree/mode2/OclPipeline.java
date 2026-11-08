package uncertaintree.mode2;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ObjLongConsumer;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import tools.vitruv.multimodelocl.OCLLexer;
import tools.vitruv.multimodelocl.OCLParser;
import tools.vitruv.multimodelocl.common.ErrorCollector;
import tools.vitruv.multimodelocl.evaluator.EvaluationVisitor;
import tools.vitruv.multimodelocl.evaluator.Value;
import tools.vitruv.multimodelocl.pipeline.MetamodelWrapperInterface;
import tools.vitruv.multimodelocl.symboltable.ScopeAnnotator;
import tools.vitruv.multimodelocl.symboltable.SymbolTableBuilder;
import tools.vitruv.multimodelocl.symboltable.SymbolTableImpl;
import tools.vitruv.multimodelocl.typechecker.TypeCheckVisitor;

/**
 * One OCL invariant, evaluated with MultiModelOCL's own stages in the order of
 * {@code OCLCompiler.compile(String)}: parse, symbol table, type check, evaluation.
 *
 * <p>The parse tree is kept between evaluations, across the analyses of one worker: it is a
 * function of the invariant's text only, and the later stages keep their results outside
 * the tree (scope annotator, {@code ParseTreeProperty} of node types). Symbol table, type
 * check and evaluation are redone on every call with fresh objects, exactly as
 * {@code OCLCompiler} does. Under instrumentation the parse of a long invariant costs
 * seconds even when warm; evaluation costs a fraction of that.
 */
final class OclPipeline {
    final String name;
    private final String text;

    /** Parse trees by invariant text, shared by all analyses of a worker (it serves one request at a time). */
    private static final Map<String, Parsed> PARSED = new ConcurrentHashMap<>();

    private record Parsed(ParseTree tree, boolean syntaxErrors) {}

    OclPipeline(String text) {
        this.text = text;
        int at = text.indexOf("inv ") + 4;
        this.name = text.substring(at, text.indexOf(':', at)).trim();
    }

    /** Verdict on the model behind {@code wrapper}; false on any compile or evaluation error, as before. */
    boolean evaluate(MetamodelWrapperInterface wrapper, ObjLongConsumer<String> timing) {
        long t = System.nanoTime();
        Parsed parsed = PARSED.get(text);
        if (parsed == null) {
            OCLParser parser = new OCLParser(new CommonTokenStream(new OCLLexer(CharStreams.fromString(text))));
            ParseTree tree = parser.contextDeclCS();
            parsed = new Parsed(tree, parser.getNumberOfSyntaxErrors() > 0);
            PARSED.put(text, parsed);
            timing.accept("parse", System.nanoTime() - t);
            t = System.nanoTime();
        }
        if (parsed.syntaxErrors()) {
            return false;
        }
        ParseTree tree = parsed.tree();
        ErrorCollector errors = new ErrorCollector();
        SymbolTableImpl symbols = new SymbolTableImpl(wrapper);
        ScopeAnnotator scopes = new ScopeAnnotator();
        new SymbolTableBuilder(symbols, wrapper, errors, scopes).visit(tree);
        if (errors.hasErrors()) {
            return false;
        }
        TypeCheckVisitor types = new TypeCheckVisitor(symbols, wrapper, errors, scopes);
        types.visit(tree);
        timing.accept("symbolsAndTypes", System.nanoTime() - t);
        if (errors.hasErrors()) {
            return false;
        }
        t = System.nanoTime();
        Value v = (Value) new EvaluationVisitor(symbols, wrapper, errors, types.getNodeTypes()).visit(tree);
        timing.accept("evaluate", System.nanoTime() - t);
        return !errors.hasErrors() && Mode2Engine.verdict(v);
    }
}
