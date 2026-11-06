set "scriptDir=%~dp0"
set "repoDir=%scriptDir%.."
call "%repoDir%\deps\dbeaver-common\mvnw.cmd" clean package -Pproduct-chromite-swing -T 1C -f "%repoDir%\product\aggregate"
