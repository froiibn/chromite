/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
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
package com.seizia.chromite.model.impl.jdbc.cache;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.DBDatabaseException;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.messages.ModelMessages;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.cache.AbstractObjectCache;
import com.seizia.chromite.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Various objects cache.
 * Simple cache which may read objects from database and keep them.
 *
 * 변경 이력:
 * - 2026-11-12 Chromite: {@link #getObjectReadingNew}(서버가 알려 준 현재 DB·스키마가 캐시에 없으면 목록을 다시 읽음): 다른 세션이 만든
 *   DB로 USE 하면 쿼리 창의 DB 선택이 "&lt; N/A &gt;"가 되던 것(앱 변경 #79, docs/chromite/swing-sql-editor-session-ui.md "캐시에 없는 현재 DB")
 * - 2026-11-12 Chromite: 다시 읽어도 없는 이름은 캐시를 비우거나 다른 이름을 물을 때까지 다시 읽지 않음: 필터로 숨긴 DB 등에서 쿼리 창의 실행마다
 *   목록 전체를 읽었다(#79 검토 후속, 같은 문서 "다시 읽어도 없는 이름")
 */
public abstract class JDBCObjectCache<OWNER extends DBSObject, OBJECT extends DBSObject> extends AbstractObjectCache<OWNER, OBJECT>
{
    private static final int DEFAULT_MAX_CACHE_SIZE = 1000000;

    private static final Log log = Log.getLog(JDBCObjectCache.class);

    // Maximum number of objects in cache
    private int maximumCacheSize = DEFAULT_MAX_CACHE_SIZE;
    // The name getObjectReadingNew read the list for without finding it: not read again for it until the cache is cleared
    // or another name is asked for (the query windows ask after every run)
    @Nullable
    private volatile String missingAfterReading;

    protected JDBCObjectCache() {
    }

    public void setMaximumCacheSize(int maximumCacheSize) {
        this.maximumCacheSize = maximumCacheSize;
    }

    @NotNull
    abstract protected JDBCStatement prepareObjectsStatement(@NotNull JDBCSession session, @NotNull OWNER owner)
        throws SQLException;

    @Nullable
    abstract protected OBJECT fetchObject(@NotNull JDBCSession session, @NotNull OWNER owner, @NotNull JDBCResultSet resultSet)
        throws SQLException, DBException;

    @NotNull
    @Override
    public List<OBJECT> getAllObjects(@NotNull DBRProgressMonitor monitor, @Nullable OWNER owner)
        throws DBException
    {
        if (!isFullyCached() && !monitor.isForceCacheUsage()) {
            loadObjects(monitor, owner);
        }
        return getCachedObjects();
    }

    @Override
    public OBJECT getObject(@NotNull DBRProgressMonitor monitor, @NotNull OWNER owner, @NotNull String name)
        throws DBException
    {
        if (!isFullyCached() && !monitor.isForceCacheUsage()) {
            this.loadObjects(monitor, owner);
        }
        return getCachedObject(name);
    }

    /**
     * The object of a name the server reports (the session's current database or schema). When the list was read before
     * the object was created (by another session or client), it is read again; the objects read before stay the same
     * instances ({@link #mergeCache}), so the navigator and open editors keep theirs.
     * <p>
     * The list is read again at most once for a name it does not hold either (hidden by an Object Explorer filter, a
     * list of one configured database, a temporary schema the list leaves out): the callers ask after every run of a
     * query window, and each would read the whole list again. Clearing the cache (Refresh, reconnect) or asking for
     * another name allows one more reading.
     *
     * @return the object, or null if the server does not list it either
     */
    @Nullable
    public OBJECT getObjectReadingNew(@NotNull DBRProgressMonitor monitor, @NotNull OWNER owner, @NotNull String name)
        throws DBException
    {
        OBJECT object = getObject(monitor, owner, name);
        if (object != null || !name.equals(missingAfterReading)) {
            missingAfterReading = null;
        }
        if (object == null && missingAfterReading == null && isFullyCached() && !monitor.isForceCacheUsage()) {
            synchronized (this) {
                setFullCache(false);
                loadObjects(monitor, owner);
            }
            object = getCachedObject(name);
            if (object == null) {
                missingAfterReading = name;
            }
        }
        return object;
    }

    @Override
    public void clearCache() {
        super.clearCache();
        missingAfterReading = null;
    }

    protected synchronized void loadObjects(DBRProgressMonitor monitor, OWNER owner)
        throws DBException
    {
        if (isFullyCached() || monitor.isForceCacheUsage() || monitor.isCanceled()) {
            return;
        }
        if (DBWorkbench.getPlatform().isUnitTestMode()) {
            log.debug("[TEST] Skip cache read in test mode");
            return;
        }

        List<OBJECT> tmpObjectList = new ArrayList<>();

        DBPDataSource dataSource = owner.getDataSource();
        if (dataSource == null) {
            throw new DBException(ModelMessages.error_not_connected_to_database);
        }
        if (owner.isPersisted()) {
            // Load cache from database only for persisted objects
            try {
                try (JDBCSession session = DBUtils.openMetaSession(monitor, owner, "Load objects from " + DBUtils.getObjectTypeName(owner) + " '" + owner.getName() + "'")) {
                    beforeCacheLoading(session, owner);
                    try (JDBCStatement dbStat = prepareObjectsStatement(session, owner)) {
                        monitor.subTask("Load " + getCacheName());
                        dbStat.setFetchSize(DBConstants.METADATA_FETCH_SIZE);
                        dbStat.executeStatement();
                        try (JDBCResultSet dbResult = dbStat.getResultSet()) {
                            if (dbResult != null) {
                                while (dbResult.next()) {
                                    if (monitor.isCanceled()) {
                                        return;
                                    }

                                    OBJECT object = fetchObject(session, owner, dbResult);
                                    if (object == null || !isValidObject(monitor, owner, object)) {
                                        continue;
                                    }
                                    tmpObjectList.add(object);

                                    // Do not log every object load. This overheats UI in case of long lists
                                    //monitor.subTask(object.getName());
                                    if (tmpObjectList.size() == maximumCacheSize) {
                                        log.warn("Maximum cache size exceeded (" + maximumCacheSize + ") in " + this);
                                        break;
                                    }
                                }
                            }
                        }
                    } finally {
                        afterCacheLoading(session, owner);
                    }
                } catch (SQLException ex) {
                    throw new DBDatabaseException(ex, dataSource);
                } catch (DBException ex) {
                    throw ex;
                } catch (Exception ex) {
                    throw new DBException("Internal driver error", ex);
                }
            } catch (Exception e) {
                if (!handleCacheReadError(e)) {
                    throw e;
                }
            }
        }

        addCustomObjects(monitor, owner, tmpObjectList);

        Comparator<OBJECT> comparator = getListOrderComparator();
        if (comparator != null && !CommonUtils.isEmpty(tmpObjectList)) {
            tmpObjectList.sort(comparator);
        }

        detectCaseSensitivity(owner);
        mergeCache(tmpObjectList);
        this.invalidateObjects(monitor, owner, new CacheIterator());
    }

    public void beforeCacheLoading(@NotNull JDBCSession session, OWNER owner) throws DBException {
        // Do nothing
    }

    public void afterCacheLoading(@NotNull JDBCSession session, OWNER owner) throws DBException {
        // Do nothing
    }

    @NotNull
    protected String getCacheName() {
        return getClass().getSimpleName();
    }

    // Can be implemented to provide custom cache error handler
    protected boolean handleCacheReadError(@NotNull Exception error) {
        return false;
    }

}
