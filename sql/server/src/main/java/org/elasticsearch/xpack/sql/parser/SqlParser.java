/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.parser;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.misc.Pair;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.xpack.sql.expression.Expression;
import org.elasticsearch.xpack.sql.plan.logical.LogicalPlan;
import org.joda.time.DateTimeZone;

import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

public class SqlParser {
    private static final Logger log = Loggers.getLogger(SqlParser.class);

    /**
     * Time zone in which the SQL is parsed. This is attached to functions
     * that deal with dates and times.
     */
    private DateTimeZone timeZone;

    public SqlParser(DateTimeZone timeZone) {
        this.timeZone = timeZone;
    }

    public LogicalPlan createStatement(String sql) {
        if (log.isDebugEnabled()) {
            log.debug("Parsing as statement: {}", sql);
        }
        return invokeParser("statement", sql, SqlBaseParser::singleStatement, AstBuilder::plan);
    }

    public Expression createExpression(String expression) {
        if (log.isDebugEnabled()) {
            log.debug("Parsing as expression: {}", expression);
        }

        return invokeParser("expression", expression, SqlBaseParser::singleExpression, AstBuilder::expression);
    }

    private <T> T invokeParser(String name, String sql, Function<SqlBaseParser, ParserRuleContext> parseFunction, BiFunction<AstBuilder, ParserRuleContext, T> visitor) {
        try {
            SqlBaseLexer lexer = new SqlBaseLexer(new CaseInsensitiveStream(sql));

            lexer.removeErrorListeners();
            lexer.addErrorListener(ERROR_LISTENER);

            CommonTokenStream tokenStream = new CommonTokenStream(lexer);
            SqlBaseParser parser = new SqlBaseParser(tokenStream);

            parser.addParseListener(new PostProcessor(Arrays.asList(parser.getRuleNames())));
            parser.removeErrorListeners();
            parser.addErrorListener(ERROR_LISTENER);

            ParserRuleContext tree;
            try {
                // first, try parsing with potentially faster SLL mode
                parser.getInterpreter().setPredictionMode(PredictionMode.SLL);
                tree = parseFunction.apply(parser);
            }
            catch (Exception ex) {
                // if we fail, parse with LL mode
                tokenStream.reset(); // rewind input stream
                parser.reset();

                parser.getInterpreter().setPredictionMode(PredictionMode.LL_EXACT_AMBIG_DETECTION);
                tree = parseFunction.apply(parser);
            }

            postProcess(lexer, parser, tree);

            return visitor.apply(new AstBuilder(timeZone), tree);
        }

        catch (StackOverflowError e) {
            throw new ParsingException(name + " is too large (stack overflow while parsing)");
        }
    }

    protected void postProcess(SqlBaseLexer lexer, SqlBaseParser parser, ParserRuleContext tree) {
        // no-op
    }

    private class PostProcessor extends SqlBaseBaseListener {
        private final List<String> ruleNames;

        PostProcessor(List<String> ruleNames) {
            this.ruleNames = ruleNames;
        }

        @Override
        public void exitBackQuotedIdentifier(SqlBaseParser.BackQuotedIdentifierContext context) {
            Token token = context.BACKQUOTED_IDENTIFIER().getSymbol();
            throw new ParsingException(
                    "backquoted indetifiers not supported; please use double quotes instead",
                    null,
                    token.getLine(),
                    token.getCharPositionInLine());
        }

        @Override
        public void exitDigitIdentifier(SqlBaseParser.DigitIdentifierContext context) {
            Token token = context.DIGIT_IDENTIFIER().getSymbol();
            throw new ParsingException(
                    "identifiers must not start with a digit; please use double quotes",
                    null,
                    token.getLine(),
                    token.getCharPositionInLine());
        }

        @Override
        public void exitQuotedIdentifier(SqlBaseParser.QuotedIdentifierContext context) {
            // Remove quotes
            context.getParent().removeLastChild();

            Token token = (Token) context.getChild(0).getPayload();
            context.getParent().addChild(new CommonToken(
                    new Pair<>(token.getTokenSource(), token.getInputStream()),
                    SqlBaseLexer.IDENTIFIER,
                    token.getChannel(),
                    token.getStartIndex() + 1,
                    token.getStopIndex() - 1));
        }

        @Override
        public void exitNonReserved(SqlBaseParser.NonReservedContext context) {
            // tree cannot be modified during rule enter/exit _unless_ it's a terminal node
            if (!(context.getChild(0) instanceof TerminalNode)) {
                int rule = ((ParserRuleContext) context.getChild(0)).getRuleIndex();
                throw new ParsingException("nonReserved can only contain tokens. Found nested rule: " + ruleNames.get(rule));
            }

            // replace nonReserved words with IDENT tokens
            context.getParent().removeLastChild();

            Token token = (Token) context.getChild(0).getPayload();
            context.getParent().addChild(new CommonToken(
                    new Pair<>(token.getTokenSource(), token.getInputStream()),
                    SqlBaseLexer.IDENTIFIER,
                    token.getChannel(),
                    token.getStartIndex(),
                    token.getStopIndex()));
        }
    }

    private static final BaseErrorListener ERROR_LISTENER = new BaseErrorListener() {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String message, RecognitionException e) {
            throw new ParsingException(message, e, line, charPositionInLine);
        }
    };
}
