set "scriptDir=%~dp0"
set "repoDir=%scriptDir%.."
call "%repoDir%\deps\dbeaver-common\mvnw.cmd" clean package -Pproduct-dbeaver-ce,product-dbeaver-eclipse-ce,appstore -T 1C -f "%repoDir%\product\aggregate"
