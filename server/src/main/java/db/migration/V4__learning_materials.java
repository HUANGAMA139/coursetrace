package db.migration;

import org.flywaydb.core.api.migration.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.regex.Pattern;

public class V4__learning_materials extends BaseJavaMigration {
    private String sql(){
        try(var input=getClass().getResourceAsStream("/db/learning-schema-v4.sql")){
            if(input==null)throw new IllegalStateException("Missing V4 schema");
            return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    @Override public Integer getChecksum(){return sql().hashCode();}
    @Override public void migrate(Context context) throws Exception {
        var connection=context.getConnection();
        boolean mysql=connection.getMetaData().getDatabaseProductName().contains("MySQL");
        try(var statement=connection.createStatement()){
            for(String part:sql().split(";")){
                String command=part.strip();if(command.isEmpty())continue;
                var add=Pattern.compile("ALTER TABLE (\\w+) ADD COLUMN (\\w+)",Pattern.CASE_INSENSITIVE).matcher(command);
                if(add.find()&&columnExists(connection,add.group(1),add.group(2)))continue;
                var drop=Pattern.compile("ALTER TABLE (\\w+) DROP CONSTRAINT (\\w+)",Pattern.CASE_INSENSITIVE).matcher(command);
                if(drop.find()){
                    if(!constraintExists(connection,drop.group(1),drop.group(2)))continue;
                    if(mysql)command=command.replace("DROP CONSTRAINT","DROP INDEX");
                }
                statement.execute(command);
            }
        }
    }
    private boolean columnExists(Connection connection,String table,String column) throws Exception {
        try(var result=connection.getMetaData().getColumns(connection.getCatalog(),null,table,column)){return result.next();}
    }
    private boolean constraintExists(Connection connection,String table,String name) throws Exception {
        try(var query=connection.prepareStatement("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE LOWER(TABLE_NAME)=? AND LOWER(CONSTRAINT_NAME)=?")){
            query.setString(1,table);query.setString(2,name);
            try(var result=query.executeQuery()){result.next();return result.getInt(1)>0;}
        }
    }
}
