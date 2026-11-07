package com.notes.notes.forms.service;

import com.notes.notes.forms.entity.FieldType;
import com.notes.notes.forms.entity.FormField;
import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * D8 CALCULATED fields: a formula over other numeric fields on the same form
 * (e.g. "qty * gw"), recomputed server-side on every save - never trust a
 * client-submitted calculated value. exp4j only understands arithmetic (no
 * ScriptEngine/eval, so no code execution risk from an admin-entered formula).
 */
@Service
public class CalculatedFieldEvaluator {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** Throws if {@code formula} refers to a field that doesn't exist, or to itself/a cycle. */
    public void validateNoCycles(FormField calculatedField, String formula, List<FormField> allFields) {
        Set<String> referenced = extractVariableNames(formula);
        Map<String, FormField> byKey = allFields.stream()
                .collect(java.util.stream.Collectors.toMap(FormField::getFieldKey, f -> f));

        for (String varName : referenced) {
            FormField referencedField = byKey.get(varName);
            if (referencedField == null) {
                throw new IllegalArgumentException("Formula references unknown field: " + varName);
            }
            if (referencedField.getId() != null && referencedField.getId().equals(calculatedField.getId())) {
                throw new IllegalArgumentException("Formula cannot reference itself: " + varName);
            }
            if (referencedField.getType() == FieldType.CALCULATED) {
                // one level deep is enough to catch the common cycle case without a full graph walk
                throw new IllegalArgumentException(
                        "Formula cannot reference another CALCULATED field: " + varName);
            }
        }
    }

    public BigDecimal evaluate(String formula, Map<String, Object> submittedValues) {
        Set<String> variableNames = extractVariableNames(formula);
        ExpressionBuilder builder = new ExpressionBuilder(formula);
        for (String name : variableNames) {
            builder.variable(name);
        }
        Expression expression = builder.build();
        for (String name : variableNames) {
            Object value = submittedValues.get(name);
            expression.setVariable(name, toDouble(value));
        }
        double result = expression.evaluate();
        return BigDecimal.valueOf(result);
    }

    private Set<String> extractVariableNames(String formula) {
        Set<String> names = new java.util.LinkedHashSet<>();
        Matcher matcher = VARIABLE_PATTERN.matcher(formula);
        while (matcher.find()) {
            names.add(matcher.group());
        }
        return names;
    }

    private double toDouble(Object value) {
        if (value == null) return 0d;
        if (value instanceof Number n) return n.doubleValue();
        return Double.parseDouble(String.valueOf(value));
    }
}
