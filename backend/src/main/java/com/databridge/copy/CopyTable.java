package com.databridge.copy;

import java.util.regex.Pattern;

public record CopyTable(String sourceSchema, String sourceTable,
                        String targetSchema, String targetTable) {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public CopyTable {
        validate(sourceSchema);
        validate(sourceTable);
        validate(targetSchema);
        validate(targetTable);
    }

    public String sourceQualifiedName() {
        return "`" + sourceSchema + "`.`" + sourceTable + "`";
    }

    public String targetQualifiedName() {
        return "\"" + targetSchema + "\".\"" + targetTable + "\"";
    }

    private static void validate(String identifier) {
        if (identifier == null || !IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Invalid SQL identifier: " + identifier);
        }
    }
}
