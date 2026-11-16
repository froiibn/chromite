# Adding a new database driver

Note: For many drivers, updating `plugin.xml` alone is enough — you only need to implement Java classes when the existing JDBC infrastructure does not cover your use case.

- Create `plugins/com.seizia.chromite.ext.{db}/` with `META-INF/MANIFEST.MF`, `plugin.xml`, and a `pom.xml` (`eclipse-plugin`).
- Add an optionally-UI sibling `plugins/com.seizia.chromite.ext.{db}.ui/`.
- Implement `DBPDataSourceProvider<YourDataSource>` → register it in `plugin.xml` under `com.seizia.chromite.dataSourceProvider`.
- Implement `JDBCDataSource` (from `com.seizia.chromite.model.jdbc`) for JDBC-based drivers.
- Implement `SQLDialect` (or extend `JDBCSQLDialect`) for SQL syntax specifics.
- Add the new plugin to `plugins/pom.xml` `<modules>` list.
- Add a test plugin `test/com.seizia.chromite.ext.{db}.test/` and register it in `test/pom.xml`.
- It is recommended to use `com.seizia.chromite.ext.generic` plugin as a base plugin (most database plugins depend on it).
- Do not copy-paste existing code especially localization bundles, SQL queries, etc. Reuse existing code instead.
- If you need to modify database navigator tree structure use `treeInjection` in plugin.xml instead of fully copy-pasting generic tree. Most databases have very similar structure and it is better to reuse it.
