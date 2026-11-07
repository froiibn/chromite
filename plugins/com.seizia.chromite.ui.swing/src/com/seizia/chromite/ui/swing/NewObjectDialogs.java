/*
 * Chromite - Database Workbench
 * Copyright (C) 2026 Chromite contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.seizia.chromite.ui.swing;

import com.seizia.chromite.Log;
import com.seizia.chromite.ext.mssql.model.SQLServerDataSource;
import com.seizia.chromite.ext.mssql.model.SQLServerDatabase;
import com.seizia.chromite.ext.mssql.model.SQLServerLogin;
import com.seizia.chromite.ext.mysql.model.MySQLCatalog;
import com.seizia.chromite.ext.mysql.model.MySQLCharset;
import com.seizia.chromite.ext.mysql.model.MySQLCollation;
import com.seizia.chromite.ext.mysql.model.MySQLDataSource;
import com.seizia.chromite.ext.postgresql.model.PostgreAvailableExtension;
import com.seizia.chromite.ext.postgresql.model.PostgreCharset;
import com.seizia.chromite.ext.postgresql.model.PostgreCollation;
import com.seizia.chromite.ext.postgresql.model.PostgreDataSource;
import com.seizia.chromite.ext.postgresql.model.PostgreDatabase;
import com.seizia.chromite.ext.postgresql.model.PostgreExtension;
import com.seizia.chromite.ext.postgresql.model.PostgreRole;
import com.seizia.chromite.ext.postgresql.model.PostgreSchema;
import com.seizia.chromite.ext.postgresql.model.PostgreServerExtension;
import com.seizia.chromite.ext.postgresql.model.PostgreTablespace;
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.DBPNamedObject2;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.DBObjectNameCaseTransformer;
import com.seizia.chromite.model.impl.edit.DBECommandAbstract;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.DBRRunnableWithReturn;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.properties.ObjectAttributeDescriptor;
import com.seizia.chromite.runtime.properties.ObjectPropertyDescriptor;
import com.seizia.chromite.ui.swing.internal.CreateObjectMessages;
import com.seizia.chromite.ui.swing.internal.SecurityMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Object Explorer "New …" 명령의 설정 대화상자: 데이터베이스(PostgreSQL, SQL Server, MySQL/MariaDB), PostgreSQL 스키마·테이블스페이스·확장,
 * 그 밖의 개체 이름. 대화상자는 고른 값만 받고, 새 모델 객체에 넣는 일({@link Configurer})은 명령 묶음을 만드는 작업 스레드가 한다.
 *
 * 설계:
 * <ul>
 *     <li>SWT의 구성기({@code PostgreDatabaseConfigurator}·{@code PostgreCreateDatabaseDialog}, {@code SQLServerCreateDatabaseDialog},
 *     {@code MySQLCreateDatabaseDialog}, {@code PostgreCreateSchemaDialog}, {@code PostgreCreateTablespaceDialog},
 *     {@code PostgreCreateExtensionDialog})가 받던 값을 받아 같은 모델 setter로 넣는다. 그래서 문장은 드라이버의 객체 관리자가 만든다
 *     ({@code PostgreDatabaseManager} 등). 목록(소유자, 인코딩, 정렬 규칙 …)은 대화상자를 띄우기 전에 진행 대화상자에서 읽는다.</li>
 *     <li>고르지 않은 값은 "(기본값)"이고 문장에서 빠진다(서버나 템플릿의 값을 따름). SWT는 소유자·인코딩·테이블스페이스를 현재 DB의 값으로
 *     미리 골라 문장에 늘 넣었는데, 빼도 결과가 같고 스크립트가 짧아 사용자가 바꾼 값만 보인다. 테이블스페이스 소유자는 모델 문장이
 *     OWNER를 늘 쓰므로 SWT처럼 현재 DB의 소유자를 미리 고른다.</li>
 *     <li>SQL Server: 모델의 {@code CREATE DATABASE}는 이름만 쓴다. SSMS 새 데이터베이스처럼 소유자, 정렬 규칙, 복구 모델을 받아
 *     같은 객체의 추가 명령({@link ExtraActionsCommand})이 {@code ALTER AUTHORIZATION}·{@code ALTER DATABASE … COLLATE}·
 *     {@code SET RECOVERY}를 덧붙인다. CREATE DATABASE는 사용자 트랜잭션 안에서 실행할 수 없으므로 이 명령이 자동 커밋으로 저장하게 한다
 *     ({@link DBECommandContext#OPTION_AVOID_TRANSACTIONS}).</li>
 *     <li>PostgreSQL: SWT의 값(소유자, 템플릿, 인코딩, 테이블스페이스)에 LC_COLLATE·LC_CTYPE를 더했다({@code PostgreDatabase#setCollate}).
 *     목록은 pg_collation의 로캘이고 직접 쓸 수도 있다. 템플릿과 다른 로캘은 template0이 필요하다(서버 오류가 알려 줌).</li>
 *     <li>이름 대화상자: 모델이 필수 암호 속성({@code @Property(password = true, required = true)})을 선언한 종류(Exasol 로컬 사용자,
 *     Mimer 프로그램)는 암호와 확인을 가려서 받는다. 드라이버마다 대화상자를 두지 않고 모델의 선언을 보므로 ui.swing이 그 드라이버에
 *     의존하지 않는다. 미리 보기에는 가린 값({@link #MASKED_PASSWORD})이 나가고 실제 암호는 저장하는 동안에만 객체에 들어간다
 *     ({@link NameAndPassword}, {@link ExplorerObjectCommands.Secrets}). 선택 사항인 암호는 묻지 않는다(관리자가 문장에서 뺌).</li>
 * </ul>
 * 목록을 읽거나 대화상자를 띄우는 메서드는 EDT에서 부르고, {@link Configurer}는 작업 스레드에서 부른다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-create-objects.md)
 * - 2026-11-08 이름 대화상자가 필수 암호를 가려서 받음 (swing-create-objects.md "새 사용자의 암호")
 * - 2026-11-08 {@link #hasUnaskedRequiredValue}: 이름 대화상자가 묻지 않는 필수 값이 있는 종류(Exasol CONNECTION의 연결 문자열)를
 *   이름만 길에서 빼도록 알림 (docs/chromite/password-handling.md)
 */
final class NewObjectDialogs {

    /**
     * Puts the chosen values into the new (unsaved) object; job thread. It may add commands for the object to the context.
     */
    @FunctionalInterface
    interface Configurer {
        void configure(@NotNull DBRProgressMonitor monitor, @NotNull DBSObject object, @NotNull DBECommandContext context) throws DBException;

        /**
         * The new object's secrets, masked in the script preview and put in only for the save; null if it has none.
         */
        @Nullable
        default ExplorerObjectCommands.Secrets getSecrets() {
            return null;
        }
    }

    /**
     * A combo item; a null value is "(default)" (no clause in the statement).
     */
    private record Choice<T>(@NotNull String label, @Nullable T value) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final Log log = Log.getLog(NewObjectDialogs.class);

    private static final String[] SQL_SERVER_RECOVERY_MODELS = {"FULL", "SIMPLE", "BULK_LOGGED"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /**
     * The password a new object holds while its script is shown (as the security editor's preview).
     */
    private static final String MASKED_PASSWORD = "********"; //$NON-NLS-1$

    private NewObjectDialogs() {
    }

    /**
     * True if the class has its own dialog here (not only a name).
     */
    static boolean hasDialog(@NotNull Class<?> childClass) {
        return PostgreDatabase.class.isAssignableFrom(childClass) || SQLServerDatabase.class.isAssignableFrom(childClass)
            || MySQLCatalog.class.isAssignableFrom(childClass) || PostgreSchema.class.isAssignableFrom(childClass)
            || PostgreTablespace.class.isAssignableFrom(childClass) || PostgreExtension.class.isAssignableFrom(childClass);
    }

    /**
     * Asks the settings of a new object of the class in the container. EDT.
     *
     * @return what to put into the new object, or null if canceled or the lists could not be read
     */
    @Nullable
    static Configurer ask(@NotNull Component owner, @NotNull String title, @NotNull DBSObject container, @NotNull Class<?> childClass) {
        if (PostgreDatabase.class.isAssignableFrom(childClass) && container instanceof PostgreDataSource dataSource) {
            return askPostgreDatabase(owner, title, dataSource);
        } else if (SQLServerDatabase.class.isAssignableFrom(childClass) && container instanceof SQLServerDataSource dataSource) {
            return askSqlServerDatabase(owner, title, dataSource);
        } else if (MySQLCatalog.class.isAssignableFrom(childClass) && container instanceof MySQLDataSource dataSource) {
            return askMySQLDatabase(owner, title, dataSource);
        } else if (PostgreSchema.class.isAssignableFrom(childClass) && container instanceof PostgreDatabase database) {
            return askPostgreSchema(owner, title, database);
        } else if (PostgreTablespace.class.isAssignableFrom(childClass) && container instanceof PostgreDatabase database) {
            return askPostgreTablespace(owner, title, database);
        } else if (PostgreExtension.class.isAssignableFrom(childClass) && container instanceof PostgreDatabase database) {
            return askPostgreExtension(owner, title, database);
        }
        return askName(owner, title, childClass);
    }

    //////////////////////////////////////////////////////////
    // Name only (and the required password)

    @Nullable
    private static Configurer askName(@NotNull Component owner, @NotNull String title, @NotNull Class<?> childClass) {
        ObjectPropertyDescriptor passwordProperty = getRequiredPassword(childClass);
        JTextField nameField = new JTextField(28);
        JPasswordField passwordField = new JPasswordField(28);
        JPasswordField confirmField = new JPasswordField(28);
        JPanel form = new JPanel(new GridBagLayout());
        DesignerDialogs.addRow(form, 0, CreateObjectMessages.create_label_name, nameField);
        if (passwordProperty != null) {
            DesignerDialogs.addRow(form, 1, SecurityMessages.security_label_password, passwordField);
            DesignerDialogs.addRow(form, 2, SecurityMessages.security_label_confirm_password, confirmField);
        }
        String name = DesignerDialogs.showDialog(owner, title, form, () -> {
            String text = nameField.getText().trim();
            if (text.isEmpty()) {
                return DesignerDialogs.error(CreateObjectMessages.create_error_name);
            }
            if (passwordProperty != null) {
                char[] password = passwordField.getPassword();
                char[] confirm = confirmField.getPassword();
                try {
                    if (password.length == 0) {
                        return DesignerDialogs.error(CreateObjectMessages.create_error_password);
                    }
                    if (!Arrays.equals(password, confirm)) {
                        // Shorter than the security editor's message: the error line shares the row with the buttons
                        return DesignerDialogs.error(CreateObjectMessages.create_error_password_mismatch);
                    }
                } finally {
                    Arrays.fill(password, '\0');
                    Arrays.fill(confirm, '\0');
                }
            }
            return text;
        });
        if (name == null) {
            return null;
        }
        if (passwordProperty == null) {
            return (monitor, object, context) -> setName(object, name);
        }
        String password = new String(passwordField.getPassword());
        passwordField.setText(""); //$NON-NLS-1$
        confirmField.setText(""); //$NON-NLS-1$
        return new NameAndPassword(name, passwordProperty, password);
    }

    /**
     * The required password property of the type ({@code @Property(password = true, required = true)}, editable), or
     * null. Such a type cannot be created without a secret, which no manager may make up (for example an Exasol local
     * user or a Mimer program ident), so the name dialog asks for it.
     */
    @Nullable
    private static ObjectPropertyDescriptor getRequiredPassword(@NotNull Class<?> childClass) {
        for (ObjectPropertyDescriptor property : ObjectAttributeDescriptor.extractAnnotations(null, childClass, null, null, false)) {
            if (property.isPassword() && property.isRequired() && property.isEditPossible()) {
                return property;
            }
        }
        return null;
    }

    /**
     * Whether the type declares a required editable value that the name dialog does not ask for: anything but the name
     * and the required password (for example the connection string of an Exasol CONNECTION). Such a type cannot be made
     * from a name, so the name-only route leaves it out instead of building a statement that fails. EDT, no database
     * access.
     */
    static boolean hasUnaskedRequiredValue(@NotNull Class<?> childClass) {
        for (ObjectPropertyDescriptor property : ObjectAttributeDescriptor.extractAnnotations(null, childClass, null, null, false)) {
            if (property.isRequired() && property.isEditPossible() && !property.isPassword()
                && !DBConstants.PROP_ID_NAME.equals(property.getId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Name and required password of a new object. The object gets a masked password for the script preview and the
     * real one only while it is saved ({@link ExplorerObjectCommands.Secrets}).
     */
    private static final class NameAndPassword implements Configurer, ExplorerObjectCommands.Secrets {
        private final String name;
        private final ObjectPropertyDescriptor property;
        private final String password;

        NameAndPassword(@NotNull String name, @NotNull ObjectPropertyDescriptor property, @NotNull String password) {
            this.name = name;
            this.property = property;
            this.password = password;
        }

        @Override
        public void configure(@NotNull DBRProgressMonitor monitor, @NotNull DBSObject object, @NotNull DBECommandContext context) throws DBException {
            setName(object, name);
            writePassword(object, MASKED_PASSWORD);
        }

        @NotNull
        @Override
        public ExplorerObjectCommands.Secrets getSecrets() {
            return this;
        }

        @Override
        public void reveal(@NotNull DBSObject object) throws DBException {
            writePassword(object, password);
        }

        @Override
        public void clear(@NotNull DBSObject object) {
            try {
                writePassword(object, ""); //$NON-NLS-1$
            } catch (DBException e) {
                log.debug("Cannot clear the password of the new object", e); //$NON-NLS-1$
            }
        }

        private void writePassword(@NotNull DBSObject object, @NotNull String value) throws DBException {
            try {
                property.writeValue(object, value);
            } catch (ReflectiveOperationException | RuntimeException | IllegalAccessError e) {
                throw new DBException("Cannot set the password of the new " + object.getClass().getSimpleName(), e);
            }
        }
    }

    /**
     * Sets the name of the new object in the database's case for unquoted names, as the table designer names new keys
     * ({@link DBObjectNameCaseTransformer}).
     */
    private static void setName(@NotNull DBSObject object, @NotNull String name) throws DBException {
        if (!(object instanceof DBPNamedObject2 named) || object.getDataSource() == null) {
            throw new DBException("The name of a new " + object.getClass().getSimpleName() + " cannot be set");
        }
        named.setName(DBObjectNameCaseTransformer.transformName(object.getDataSource(), name));
    }

    //////////////////////////////////////////////////////////
    // PostgreSQL database

    private record PostgreDatabaseLists(
        @NotNull List<PostgreRole> roles,
        @NotNull Collection<String> templates,
        @NotNull List<PostgreCharset> encodings,
        @NotNull Collection<String> locales,
        @NotNull List<PostgreTablespace> tablespaces
    ) {
    }

    @Nullable
    private static Configurer askPostgreDatabase(@NotNull Component owner, @NotNull String title, @NotNull PostgreDataSource dataSource) {
        PostgreServerExtension serverType = dataSource.getServerType();
        PostgreDatabaseLists lists = load(title, monitor -> {
            PostgreDatabase database = dataSource.getDefaultInstance();
            List<PostgreRole> roles = serverType.supportsRoles() ? new ArrayList<>(database.getUsers(monitor)) : List.of();
            Collection<String> templates = new TreeSet<>();
            if (serverType.supportsTemplates()) {
                templates.addAll(dataSource.getTemplateDatabases(monitor));
                for (PostgreDatabase other : dataSource.getDatabases()) {
                    templates.add(other.getName());
                }
            }
            List<PostgreCharset> encodings = serverType.supportsEncodings() ? new ArrayList<>(database.getEncodings(monitor)) : List.of();
            Collection<String> locales = new TreeSet<>();
            if (serverType.supportsCollations()) {
                for (PostgreCollation collation : database.getCollations(monitor)) {
                    if (!CommonUtils.isEmpty(collation.getCollate())) {
                        locales.add(collation.getCollate());
                    }
                }
            }
            List<PostgreTablespace> tablespaces = serverType.supportsTablespaces() ? new ArrayList<>(database.getTablespaces(monitor)) : List.of();
            return new PostgreDatabaseLists(roles, templates, encodings, locales, tablespaces);
        });
        if (lists == null) {
            return null;
        }
        JTextField nameField = new JTextField(28);
        JComboBox<Choice<PostgreRole>> ownerCombo = createCombo(lists.roles(), PostgreRole::getName);
        JComboBox<Choice<String>> templateCombo = createCombo(lists.templates(), s -> s);
        JComboBox<Choice<PostgreCharset>> encodingCombo = createCombo(lists.encodings(), PostgreCharset::getName);
        JComboBox<String> collateCombo = createEditableCombo(lists.locales());
        JComboBox<String> ctypeCombo = createEditableCombo(lists.locales());
        JComboBox<Choice<PostgreTablespace>> tablespaceCombo = createCombo(lists.tablespaces(), PostgreTablespace::getName);

        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_name, nameField);
        if (serverType.supportsRoles()) {
            DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_owner, ownerCombo);
        }
        if (serverType.supportsTemplates()) {
            DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_template, templateCombo);
        }
        if (serverType.supportsEncodings()) {
            DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_encoding, encodingCombo);
        }
        if (serverType.supportsCollations()) {
            DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_lc_collate, collateCombo);
            DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_lc_ctype, ctypeCombo);
        }
        if (serverType.supportsTablespaces()) {
            DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_tablespace, tablespaceCombo);
        }
        addHint(form, row, CreateObjectMessages.create_hint_defaults);

        record Values(String name, PostgreRole owner, String template, PostgreCharset encoding, String collate, String ctype,
                      PostgreTablespace tablespace) {
        }
        Values values = DesignerDialogs.showDialog(owner, title, form, () -> {
            String name = nameField.getText().trim();
            if (name.isEmpty()) {
                return DesignerDialogs.error(CreateObjectMessages.create_error_name);
            }
            return new Values(name, selected(ownerCombo), selected(templateCombo), selected(encodingCombo), editedText(collateCombo),
                editedText(ctypeCombo), selected(tablespaceCombo));
        });
        if (values == null) {
            return null;
        }
        return (monitor, object, context) -> {
            PostgreDatabase database = (PostgreDatabase) object;
            database.setName(values.name());
            database.setInitialOwner(values.owner());
            database.setTemplateName(values.template());
            database.setInitialEncoding(values.encoding());
            database.setCollate(values.collate());
            database.setCtype(values.ctype());
            database.setInitialTablespace(values.tablespace());
        };
    }

    //////////////////////////////////////////////////////////
    // SQL Server database

    private record SqlServerDatabaseLists(@NotNull List<SQLServerLogin> logins, @NotNull List<String> collations) {
    }

    @Nullable
    private static Configurer askSqlServerDatabase(@NotNull Component owner, @NotNull String title, @NotNull SQLServerDataSource dataSource) {
        SqlServerDatabaseLists lists = load(title, monitor -> {
            List<SQLServerLogin> logins = new ArrayList<>();
            for (SQLServerLogin login : dataSource.getLogins(monitor)) {
                // Server principals that cannot own a database are not offered (certificate-mapped ##...## logins)
                if (!login.getName().startsWith("##")) { //$NON-NLS-1$
                    logins.add(login);
                }
            }
            SQLServerDatabase database = dataSource.getDefaultDatabase(monitor);
            return new SqlServerDatabaseLists(logins, database == null ? List.of() : database.getCollationNames(monitor));
        });
        if (lists == null) {
            return null;
        }
        JTextField nameField = new JTextField(28);
        JComboBox<Choice<SQLServerLogin>> ownerCombo = createCombo(lists.logins(), SQLServerLogin::getName);
        JComboBox<String> collationCombo = createEditableCombo(lists.collations());
        JComboBox<Choice<String>> recoveryCombo = createCombo(List.of(SQL_SERVER_RECOVERY_MODELS), s -> s);

        JPanel form = new JPanel(new GridBagLayout());
        DesignerDialogs.addRow(form, 0, CreateObjectMessages.create_label_name, nameField);
        DesignerDialogs.addRow(form, 1, CreateObjectMessages.create_label_owner, ownerCombo);
        DesignerDialogs.addRow(form, 2, CreateObjectMessages.create_label_collation, collationCombo);
        DesignerDialogs.addRow(form, 3, CreateObjectMessages.create_label_recovery, recoveryCombo);
        addHint(form, 4, CreateObjectMessages.create_hint_defaults);

        record Values(String name, SQLServerLogin owner, String collation, String recovery) {
        }
        Values values = DesignerDialogs.showDialog(owner, title, form, () -> {
            String name = nameField.getText().trim();
            if (name.isEmpty()) {
                return DesignerDialogs.error(CreateObjectMessages.create_error_name);
            }
            String collation = editedText(collationCombo);
            if (collation != null && !collation.matches("\\w+")) { //$NON-NLS-1$
                return DesignerDialogs.error(CreateObjectMessages.create_error_collation);
            }
            return new Values(name, selected(ownerCombo), collation, selected(recoveryCombo));
        });
        if (values == null) {
            return null;
        }
        return (monitor, object, context) -> {
            SQLServerDatabase database = (SQLServerDatabase) object;
            database.setName(values.name());
            String quotedName = DBUtils.getQuotedIdentifier(database);
            List<DBEPersistAction> actions = new ArrayList<>();
            if (values.collation() != null) {
                actions.add(new SQLDatabasePersistAction("Set collation", "ALTER DATABASE " + quotedName + " COLLATE " + values.collation()));
            }
            if (values.owner() != null) {
                actions.add(new SQLDatabasePersistAction("Set owner", "ALTER AUTHORIZATION ON DATABASE::" + quotedName + " TO "
                    + DBUtils.getQuotedIdentifier(values.owner())));
            }
            if (values.recovery() != null) {
                actions.add(new SQLDatabasePersistAction("Set recovery model", "ALTER DATABASE " + quotedName + " SET RECOVERY " + values.recovery()));
            }
            // Also without extra statements: CREATE DATABASE must not run in a user transaction
            context.addCommand(new ExtraActionsCommand(database, actions, true), null, false);
        };
    }

    //////////////////////////////////////////////////////////
    // MySQL / MariaDB database

    @Nullable
    private static Configurer askMySQLDatabase(@NotNull Component owner, @NotNull String title, @NotNull MySQLDataSource dataSource) {
        JTextField nameField = new JTextField(28);
        List<MySQLCharset> charsets = new ArrayList<>(dataSource.getCharsets());
        JComboBox<Choice<MySQLCharset>> charsetCombo = createCombo(charsets, MySQLCharset::getName);
        JComboBox<Choice<MySQLCollation>> collationCombo = new JComboBox<>();
        Runnable fillCollations = () -> {
            collationCombo.removeAllItems();
            collationCombo.addItem(new Choice<>(CreateObjectMessages.create_default, null));
            MySQLCharset charset = selected(charsetCombo);
            if (charset != null) {
                for (MySQLCollation collation : charset.getCollations()) {
                    collationCombo.addItem(new Choice<>(collation.getName(), collation));
                }
            }
        };
        charsetCombo.addActionListener(e -> fillCollations.run());
        fillCollations.run();

        JPanel form = new JPanel(new GridBagLayout());
        DesignerDialogs.addRow(form, 0, CreateObjectMessages.create_label_name, nameField);
        DesignerDialogs.addRow(form, 1, CreateObjectMessages.create_label_charset, charsetCombo);
        DesignerDialogs.addRow(form, 2, CreateObjectMessages.create_label_collation, collationCombo);
        addHint(form, 3, CreateObjectMessages.create_hint_defaults);

        record Values(String name, MySQLCharset charset, MySQLCollation collation) {
        }
        Values values = DesignerDialogs.showDialog(owner, title, form, () -> {
            String name = nameField.getText().trim();
            if (name.isEmpty()) {
                return DesignerDialogs.error(CreateObjectMessages.create_error_name);
            }
            return new Values(name, selected(charsetCombo), selected(collationCombo));
        });
        if (values == null) {
            return null;
        }
        return (monitor, object, context) -> {
            MySQLCatalog catalog = (MySQLCatalog) object;
            catalog.setName(values.name());
            catalog.getAdditionalInfo().setDefaultCharset(values.charset());
            catalog.getAdditionalInfo().setDefaultCollation(values.collation());
        };
    }

    //////////////////////////////////////////////////////////
    // PostgreSQL schema, tablespace, extension

    @Nullable
    private static Configurer askPostgreSchema(@NotNull Component owner, @NotNull String title, @NotNull PostgreDatabase database) {
        boolean roles = database.getDataSource().getServerType().supportsRoles();
        List<PostgreRole> users = roles ? load(title, monitor -> new ArrayList<>(database.getUsers(monitor))) : List.of();
        if (users == null) {
            return null;
        }
        JTextField nameField = new JTextField(28);
        JTextField databaseField = createReadOnlyField(database.getName());
        JComboBox<Choice<PostgreRole>> ownerCombo = createCombo(users, PostgreRole::getName);
        JPanel form = new JPanel(new GridBagLayout());
        DesignerDialogs.addRow(form, 0, CreateObjectMessages.create_label_database, databaseField);
        DesignerDialogs.addRow(form, 1, CreateObjectMessages.create_label_name, nameField);
        if (roles) {
            DesignerDialogs.addRow(form, 2, CreateObjectMessages.create_label_owner, ownerCombo);
        }
        record Values(String name, PostgreRole owner) {
        }
        Values values = DesignerDialogs.showDialog(owner, title, form, () -> {
            String name = nameField.getText().trim();
            return name.isEmpty() ? DesignerDialogs.error(CreateObjectMessages.create_error_name) : new Values(name, selected(ownerCombo));
        });
        if (values == null) {
            return null;
        }
        return (monitor, object, context) -> {
            PostgreSchema schema = (PostgreSchema) object;
            schema.setName(values.name());
            schema.setOwner(values.owner());
        };
    }

    @Nullable
    private static Configurer askPostgreTablespace(@NotNull Component owner, @NotNull String title, @NotNull PostgreDatabase database) {
        record Lists(List<PostgreRole> users, PostgreRole dba) {
        }
        Lists lists = load(title, monitor -> new Lists(new ArrayList<>(database.getUsers(monitor)), database.getDBA(monitor)));
        if (lists == null) {
            return null;
        }
        boolean location = database.getDataSource().getServerType().supportsTablespaceLocation();
        JTextField nameField = new JTextField(28);
        JComboBox<PostgreRole> ownerCombo = new JComboBox<>(lists.users().toArray(new PostgreRole[0]));
        ownerCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                return super.getListCellRendererComponent(list, value instanceof PostgreRole role ? role.getName() : value, index, isSelected, cellHasFocus);
            }
        });
        // The model's statement always names the owner (CREATE TABLESPACE … OWNER): SWT preselected the database owner
        if (lists.dba() != null) {
            ownerCombo.setSelectedItem(lists.dba());
        }
        JTextField locationField = new JTextField(28);
        JTextArea optionsArea = new JTextArea(3, 28);
        optionsArea.setLineWrap(true);
        optionsArea.setToolTipText(CreateObjectMessages.create_options_tip);
        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_name, nameField);
        DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_owner, ownerCombo);
        if (location) {
            DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_location, locationField);
        }
        DesignerDialogs.addRow(form, row, CreateObjectMessages.create_label_options, new JScrollPane(optionsArea));

        record Values(String name, PostgreRole owner, String location, String options) {
        }
        Values values = DesignerDialogs.showDialog(owner, title, form, () -> {
            String name = nameField.getText().trim();
            if (name.isEmpty()) {
                return DesignerDialogs.error(CreateObjectMessages.create_error_name);
            }
            if (!(ownerCombo.getSelectedItem() instanceof PostgreRole role)) {
                return DesignerDialogs.error(CreateObjectMessages.create_error_owner);
            }
            String path = locationField.getText().trim();
            // The model writes LOCATION '<path>' without escaping
            if (location && (path.isEmpty() || path.indexOf('\'') >= 0)) {
                return DesignerDialogs.error(CreateObjectMessages.create_error_location);
            }
            return new Values(name, role, location ? path : null, CommonUtils.nullIfEmpty(optionsArea.getText().trim()));
        });
        if (values == null) {
            return null;
        }
        return (monitor, object, context) -> {
            PostgreTablespace tablespace = (PostgreTablespace) object;
            tablespace.setName(values.name());
            tablespace.setOwnerId(values.owner().getObjectId());
            tablespace.setLoc(values.location());
            tablespace.setOptions(values.options());
        };
    }

    @Nullable
    private static Configurer askPostgreExtension(@NotNull Component owner, @NotNull String title, @NotNull PostgreDatabase database) {
        record Lists(List<PostgreSchema> schemas, List<PostgreAvailableExtension> extensions) {
        }
        Lists lists = load(title, monitor -> {
            List<PostgreAvailableExtension> available = new ArrayList<>();
            for (PostgreAvailableExtension extension : database.getAvailableExtensions(monitor)) {
                // Installed extensions are in the Extensions folder already (CREATE EXTENSION would fail)
                if (CommonUtils.isEmpty(extension.getInstalledVersion())) {
                    available.add(extension);
                }
            }
            return new Lists(new ArrayList<>(database.getSchemas(monitor)), available);
        });
        if (lists == null) {
            return null;
        }
        JTextField databaseField = createReadOnlyField(database.getName());
        JComboBox<PostgreSchema> schemaCombo = new JComboBox<>(lists.schemas().toArray(new PostgreSchema[0]));
        schemaCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                return super.getListCellRendererComponent(list, value instanceof PostgreSchema schema ? schema.getName() : value, index, isSelected, cellHasFocus);
            }
        });
        PostgreSchema publicSchema = DBUtils.findObject(lists.schemas(), "public"); //$NON-NLS-1$
        if (publicSchema != null) {
            schemaCombo.setSelectedItem(publicSchema);
        }
        List<PostgreAvailableExtension> extensions = lists.extensions();
        JTable extensionTable = new JTable(new AbstractTableModel() {
            @Override
            public int getRowCount() {
                return extensions.size();
            }

            @Override
            public int getColumnCount() {
                return 3;
            }

            @Override
            public String getColumnName(int column) {
                return switch (column) {
                    case 0 -> CreateObjectMessages.create_column_name;
                    case 1 -> CreateObjectMessages.create_column_version;
                    default -> CreateObjectMessages.create_column_description;
                };
            }

            @Override
            public Object getValueAt(int rowIndex, int columnIndex) {
                PostgreAvailableExtension extension = extensions.get(rowIndex);
                return switch (columnIndex) {
                    case 0 -> extension.getName();
                    case 1 -> extension.getVersion();
                    default -> extension.getDescription();
                };
            }
        });
        extensionTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        extensionTable.getColumnModel().getColumn(0).setPreferredWidth(160);
        extensionTable.getColumnModel().getColumn(1).setPreferredWidth(60);
        extensionTable.getColumnModel().getColumn(2).setPreferredWidth(360);
        JScrollPane tableScroll = new JScrollPane(extensionTable);
        tableScroll.setPreferredSize(new Dimension(580, 260));

        JPanel form = new JPanel(new GridBagLayout());
        DesignerDialogs.addRow(form, 0, CreateObjectMessages.create_label_database, databaseField);
        DesignerDialogs.addRow(form, 1, CreateObjectMessages.create_label_schema, schemaCombo);
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.add(form, BorderLayout.NORTH);
        JPanel tablePanel = new JPanel(new BorderLayout(0, 4));
        tablePanel.add(new JLabel(CreateObjectMessages.create_label_extension), BorderLayout.NORTH);
        tablePanel.add(tableScroll, BorderLayout.CENTER);
        content.add(tablePanel, BorderLayout.CENTER);

        record Values(PostgreAvailableExtension extension, PostgreSchema schema) {
        }
        Values values = DesignerDialogs.showDialog(owner, title, content, () -> {
            int selectedRow = extensionTable.getSelectedRow();
            if (selectedRow < 0) {
                return DesignerDialogs.error(CreateObjectMessages.create_error_extension);
            }
            if (!(schemaCombo.getSelectedItem() instanceof PostgreSchema schema)) {
                return DesignerDialogs.error(CreateObjectMessages.create_error_schema);
            }
            return new Values(extensions.get(extensionTable.convertRowIndexToModel(selectedRow)), schema);
        });
        if (values == null) {
            return null;
        }
        return (monitor, object, context) -> {
            PostgreExtension extension = (PostgreExtension) object;
            extension.setName(values.extension().getName());
            // The model writes "SCHEMA <schema>" as given: the quoted name keeps names that need quotes valid
            extension.setSchema(DBUtils.getQuotedIdentifier(values.schema()));
        };
    }

    //////////////////////////////////////////////////////////
    // Helpers

    /**
     * Reads the dialog's lists in a progress dialog; shows the error and returns null if that fails or is canceled.
     */
    @Nullable
    private static <T> T load(@NotNull String title, @NotNull DBRRunnableWithReturn<T> reader) {
        try {
            return ProgressDialog.runTask(NLS.bind(CreateObjectMessages.create_progress_read, title), reader);
        } catch (DBInterruptedException e) {
            return null;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(title, CreateObjectMessages.create_error_read, e);
            return null;
        }
    }

    /**
     * A combo of "(default)" and the items.
     */
    @NotNull
    private static <T> JComboBox<Choice<T>> createCombo(@NotNull Collection<T> items, @NotNull Function<T, String> label) {
        JComboBox<Choice<T>> combo = new JComboBox<>();
        combo.addItem(new Choice<>(CreateObjectMessages.create_default, null));
        for (T item : items) {
            combo.addItem(new Choice<>(label.apply(item), item));
        }
        return combo;
    }

    /**
     * An editable combo of names that starts empty (empty: the default).
     */
    @NotNull
    private static JComboBox<String> createEditableCombo(@NotNull Collection<String> items) {
        JComboBox<String> combo = new JComboBox<>();
        combo.addItem(""); //$NON-NLS-1$
        items.forEach(combo::addItem);
        combo.setEditable(true);
        combo.setSelectedIndex(0);
        return combo;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static <T> T selected(@NotNull JComboBox<Choice<T>> combo) {
        return combo.getSelectedItem() instanceof Choice<?> choice ? (T) choice.value() : null;
    }

    @Nullable
    private static String editedText(@NotNull JComboBox<String> combo) {
        Object value = combo.getEditor().getItem();
        return value == null ? null : CommonUtils.nullIfEmpty(value.toString().trim());
    }

    @NotNull
    private static JTextField createReadOnlyField(@NotNull String text) {
        JTextField field = new JTextField(text, 28);
        field.setEditable(false);
        return field;
    }

    private static void addHint(@NotNull JPanel form, int row, @NotNull String hint) {
        JLabel label = new JLabel(hint);
        label.setForeground(UIManager.getColor("Label.disabledForeground")); //$NON-NLS-1$
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.gridwidth = 2;
        constraints.anchor = GridBagConstraints.LINE_START;
        constraints.insets = new Insets(4, 0, 0, 0);
        form.add(label, constraints);
    }

    /**
     * Statements that follow the creation of an object in the same save (SQL Server database settings). With
     * {@code avoidTransactions} the save runs in auto-commit mode, as statements that must not run in a user
     * transaction need ({@code CREATE DATABASE}).
     */
    private static final class ExtraActionsCommand extends DBECommandAbstract<DBSObject> {
        private final List<DBEPersistAction> actions;
        private final boolean avoidTransactions;

        ExtraActionsCommand(@NotNull DBSObject object, @NotNull List<DBEPersistAction> actions, boolean avoidTransactions) {
            super(object, "Object settings"); //$NON-NLS-1$
            this.actions = actions;
            this.avoidTransactions = avoidTransactions;
        }

        @Override
        public void validateCommand(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> options) {
            if (avoidTransactions) {
                options.put(DBECommandContext.OPTION_AVOID_TRANSACTIONS, true);
            }
        }

        @NotNull
        @Override
        public DBEPersistAction[] getPersistActions(
            @NotNull DBRProgressMonitor monitor,
            @NotNull DBCExecutionContext executionContext,
            @NotNull Map<String, Object> options
        ) {
            return actions.toArray(new DBEPersistAction[0]);
        }
    }
}
