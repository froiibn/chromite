Chromite - bundled JDBC drivers
===============================

This folder contains third-party JDBC drivers that are shipped with
Chromite so that connections work without Internet access. They are
NOT part of Chromite and are NOT covered by the Apache License 2.0 of
Chromite. Each driver is distributed unmodified, as published on Maven
Central (https://repo1.maven.org/maven2/), under its own license.
The license texts are in the same folder as the jar files.

Chromite loads a driver's files only when a connection that uses the
driver is opened.

mssql/new/ - Microsoft SQL Server, Azure SQL Server, Babelfish
  mssql-jdbc-13.4.0.jre11.jar          MIT License (LICENSE.txt)
  msal4j-1.16.0.jar                    MIT License (LICENSE-msal4j.txt)
  oauth2-oidc-sdk-11.9.1.jar           Apache License 2.0
  nimbus-jose-jwt-9.37.3.jar           Apache License 2.0
  content-type-2.3.jar                 Apache License 2.0
  lang-tag-1.7.jar                     Apache License 2.0
  jcip-annotations-1.0-1.jar           Apache License 2.0
  json-smart-2.5.0.jar                 Apache License 2.0
  accessors-smart-2.5.0.jar            Apache License 2.0
  asm-9.3.jar                          BSD 3-Clause License (LICENSE-asm.txt)
  slf4j-api-1.7.36.jar                 MIT License (LICENSE-slf4j.txt)
  jackson-databind-2.13.4.2.jar        Apache License 2.0
  jackson-annotations-2.13.4.jar       Apache License 2.0
  jackson-core-2.13.4.jar              Apache License 2.0
  msal4j and its dependencies are used only for Microsoft Entra ID
  (Azure Active Directory) authentication.
  The Windows integrated authentication library (mssql-jdbc_auth DLL)
  is not included. To use integratedSecurity=true, add
  mssql-jdbc_auth-13.4.0.x64.dll in the driver settings.

oracle/ - Oracle
  ojdbc11-23.2.0.0.jar                 Oracle Free Use Terms and Conditions
  orai18n-23.2.0.0.jar                 (FUTC, LICENSE.txt). orai18n and
  xdb6-18.3.0.0.jar                    xmlparserv2 also carry Oracle's
  xmlparserv2-23.2.0.0.jar             terms in META-INF/license.txt.
  The programs are redistributed unmodified and free of charge, as the
  FUTC requires.

postgresql/ - PostgreSQL and PostgreSQL-compatible databases
  postgresql-42.7.14.jar               BSD 2-Clause License (LICENSE.txt)

postgis/ - PostGIS geometry types for PostgreSQL drivers
  postgis-jdbc-2.5.0.jar               GNU LGPL 2.1 (LICENSE.txt)
  postgis-geometry-2.5.0.jar           GNU LGPL 2.1
  postgis-jdbc-jtsparser-2.5.0.jar     GNU LGPL 2.1
  Source code: see SOURCE.txt.

mariadb/ - MariaDB (also usable for MySQL servers)
  mariadb-java-client-3.5.10.jar       GNU LGPL 2.1 or later (LICENSE.txt)
  Source code: see SOURCE.txt.

The Apache License 2.0 text is in licenses/external/asl-2.0.txt in the
installation folder. Jar files that contain their own LICENSE or NOTICE
files (under META-INF/) keep them.

Other drivers are not bundled. Chromite downloads them from Maven
Central when they are first used, unless offline mode is enabled in
Preferences > Connections > Drivers. In offline mode, add the jar files
manually in the driver settings.
