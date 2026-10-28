package com.hospisoft.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.model.StockState;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The three SQL fragments used by the list filter must classify exactly like {@link StockState#of}.
 * A tiny evaluator interprets the fragments literally, so changing a comparison breaks this test.
 */
class StockStateSqlParityTest {

    private static final Pattern COMPARISON = Pattern.compile("^(stock|stock_minimo)\\s*(=|<=|>=|<|>)\\s*(stock|stock_minimo|-?\\d+)$");

    /** Evaluates "a OP b [AND c OP d ...]" where operands are column names or integer literals. */
    static boolean evaluate(String fragment, int stock, int stockMinimo) {
        boolean result = true;
        for (String term : fragment.split("\\s+AND\\s+")) {
            Matcher m = COMPARISON.matcher(term.trim());
            assertTrue(m.matches(), "unsupported SQL term: " + term);
            int left = operand(m.group(1), stock, stockMinimo);
            int right = operand(m.group(3), stock, stockMinimo);
            boolean value = switch (m.group(2)) {
                case "=" -> left == right;
                case "<=" -> left <= right;
                case ">=" -> left >= right;
                case "<" -> left < right;
                case ">" -> left > right;
                default -> throw new IllegalStateException(m.group(2));
            };
            result &= value;
        }
        return result;
    }

    private static int operand(String token, int stock, int stockMinimo) {
        return switch (token) {
            case "stock" -> stock;
            case "stock_minimo" -> stockMinimo;
            default -> Integer.parseInt(token);
        };
    }

    @Test
    void sqlFragmentsAgreeWithStockStateOf() {
        List<int[]> cases = List.of(new int[] {0, 0}, new int[] {0, 5}, new int[] {1, 0}, new int[] {5, 5},
            new int[] {6, 5}, new int[] {3, 5});
        for (int[] c : cases) {
            int stock = c[0];
            int minimo = c[1];
            StockState expected = StockState.of(stock, minimo);
            for (StockState state : StockState.values()) {
                boolean matches = evaluate(MedicamentoDao.stateClause(state), stock, minimo);
                assertEquals(state == expected, matches,
                    "stock=" + stock + " min=" + minimo + " fragment " + state + " [" + MedicamentoDao.stateClause(state) + "]");
            }
        }
    }

    @Test
    void evaluatorUnderstandsTheFragments() {
        assertTrue(evaluate("stock = 0", 0, 9));
        assertTrue(evaluate("stock > 0 AND stock <= stock_minimo", 3, 5));
        assertEquals(false, evaluate("stock > 0 AND stock <= stock_minimo", 0, 5));
        assertTrue(evaluate("stock > stock_minimo", 6, 5));
    }
}
