package sqlancer.tidb.gen;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.tidb.TiDBBugs;
import sqlancer.tidb.TiDBExpressionGenerator;
import sqlancer.tidb.TiDBOptions;
import sqlancer.tidb.TiDBProvider.TiDBGlobalState;
import sqlancer.tidb.TiDBSchema.TiDBColumn;
import sqlancer.tidb.TiDBSchema.TiDBCompositeDataType;
import sqlancer.tidb.TiDBSchema.TiDBDataType;
import sqlancer.tidb.TiDBSchema.TiDBTable;
import sqlancer.tidb.visitor.TiDBVisitor;

public class TiDBTableGenerator {

    private boolean allowPrimaryKey;
    private final List<TiDBColumn> columns = new ArrayList<>();
    private boolean primaryKeyAsTableConstraints;
    private final ExpectedErrors errors = new ExpectedErrors();
    private TiDBGlobalState globalState;

    public SQLQueryAdapter getQuery(TiDBGlobalState globalState) throws SQLException {
        this.globalState = globalState;
        errors.add("Information schema is changed during the execution of the statement");
        String tableName = globalState.getSchema().getFreeTableName();
        int nrColumns = Randomly.fromOptions(1, 2, 3);
        allowPrimaryKey = Randomly.getBoolean();
        primaryKeyAsTableConstraints = allowPrimaryKey && Randomly.getBoolean();
        for (int i = 0; i < nrColumns; i++) {
            TiDBColumn fakeColumn = new TiDBColumn("c" + i, null, false, false);
            columns.add(fakeColumn);
        }
        TiDBExpressionGenerator gen = new TiDBExpressionGenerator(globalState).setColumns(columns);

        StringBuilder sb = new StringBuilder("CREATE TABLE ");
        sb.append(tableName);

        if (Randomly.getBoolean() && globalState.getSchema().getDatabaseTables().size() > 0) {
            sb.append(" LIKE ");
            TiDBTable otherTable = globalState.getSchema().getRandomTableNoViewOrBailout(); // cannot create table like a view is not BASE TABLE
            sb.append(otherTable.getName());
        } else {
            createNewTable(gen, sb);
        }
        return new SQLQueryAdapter(sb.toString(), errors, true);
    }

    private void createNewTable(TiDBExpressionGenerator gen, StringBuilder sb) {
        sb.append("(");
        for (int i = 0; i < columns.size(); i++) {
            if (i != 0) {
                sb.append(", ");
            }
            sb.append(columns.get(i).getName());
            sb.append(" ");
            TiDBCompositeDataType type;
            type = TiDBCompositeDataType.getRandom();
            appendType(sb, type);
            sb.append(" ");
            boolean isGeneratedColumn = Randomly.getBooleanWithRatherLowProbability();
            isGeneratedColumn = false; // experimental features, too much errors
            if (isGeneratedColumn) {
                sb.append(" AS (");
                sb.append(TiDBVisitor.asString(gen.generateExpression()));
                sb.append(") ");
                sb.append(Randomly.fromOptions("STORED", "VIRTUAL"));
                sb.append(" ");
                errors.add("Generated column can refer only to generated columns defined prior to it");
                errors.add(
                        "'Defining a virtual generated column as primary key' is not supported for generated columns.");
                errors.add("contains a disallowed function.");
                errors.add("cannot refer to auto-increment column");
            }
            if (Randomly.getBooleanWithRatherLowProbability()) {
                sb.append("CHECK (");
                sb.append(TiDBVisitor.asString(gen.generateExpression()));
                sb.append(") ");
            }
            if (Randomly.getBooleanWithRatherLowProbability()) {
                sb.append("NOT NULL ");
            }
            if (Randomly.getBoolean() && type.getPrimitiveDataType().canHaveDefault() && !isGeneratedColumn && globalState.getDbmsSpecificOptions().getTestOracleFactory().get(0) != TiDBOptions.TiDBOracleFactory.EDC) {
                sb.append("DEFAULT ");
                sb.append(TiDBVisitor.asString(gen.generateConstant(type.getPrimitiveDataType())));
                sb.append(" ");
                errors.add("Invalid default value");
                errors.add(
                        "All parts of a PRIMARY KEY must be NOT NULL; if you need NULL in a key, use UNIQUE instead");
            }
            if (type.getPrimitiveDataType() == TiDBDataType.INT && Randomly.getBooleanWithRatherLowProbability()
                    && !isGeneratedColumn) {
                sb.append(" AUTO_INCREMENT ");
                errors.add("there can be only one auto column and it must be defined as a key");
            }
            if (Randomly.getBooleanWithRatherLowProbability() && canUseAsUnique(type)) {
                sb.append("UNIQUE ");
            }
            if (Randomly.getBooleanWithRatherLowProbability() && allowPrimaryKey && !primaryKeyAsTableConstraints
                    && canUseAsUnique(type) && !isGeneratedColumn) {
                sb.append("PRIMARY KEY ");
                allowPrimaryKey = false;
            }
        }
        if (primaryKeyAsTableConstraints) {
            sb.append(", PRIMARY KEY(");
            sb.append(
                    Randomly.nonEmptySubset(columns).stream().map(c -> c.getName()).collect(Collectors.joining(", ")));
            sb.append(")");
            // TODO: do nto include blob/text columns here
            errors.add(" used in key specification without a key length");
        }
        sb.append(")");
        if (Randomly.getBooleanWithRatherLowProbability()
                && !TiDBBugs.bug14 /* there are also a number of unresolved other partitioning bugs */) {
            sb.append("PARTITION BY HASH(");
            sb.append(TiDBVisitor.asString(gen.generateExpression()));
            sb.append(") ");
            sb.append("PARTITIONS ");
            sb.append(Randomly.getNotCachedInteger(1, 100));
            errors.add(
                    "Constant, random or timezone-dependent expressions in (sub)partitioning function are not allowed");
            errors.add("This partition function is not allowed");
            errors.add("A PRIMARY KEY must include all columns in the table's partitioning function");
            errors.add("A UNIQUE INDEX must include all columns in the table's partitioning function");
            errors.add("is of a not allowed type for this type of partitioning");
            errors.add("The PARTITION function returns the wrong type");
            if (TiDBBugs.bug16) {
                errors.add("UnknownType: *ast.WhenClause");
            }
        }
    }

    static boolean canUseAsUnique(TiDBCompositeDataType type) {
        return type.getPrimitiveDataType() != TiDBDataType.TEXT && type.getPrimitiveDataType() != TiDBDataType.BLOB;
    }

    static void appendType(StringBuilder sb, TiDBCompositeDataType type) {
        sb.append(type.toString());
        appendSpecifiers(sb, type.getPrimitiveDataType());
        appendSizeSpecifiers(sb, type.getPrimitiveDataType());
    }

    static void appendSizeSpecifiers(StringBuilder sb, TiDBDataType type) {
        if (type.isNumeric() && Randomly.getBoolean() && !TiDBBugs.bug16028) {
            sb.append(" UNSIGNED");
        }
        if (type.isNumeric() && Randomly.getBoolean()
                && !TiDBBugs.bug16028 /* seems to be the same bug as https://github.com/pingcap/tidb/issues/16028 */) {
            sb.append(" ZEROFILL");
        }
    }

    static void appendSpecifiers(StringBuilder sb, TiDBDataType type) {
        if (type == TiDBDataType.TEXT || type == TiDBDataType.BLOB) {
            sb.append("(");
            sb.append(Randomly.getNotCachedInteger(1, 500));
            sb.append(")");
        }
    }
}
