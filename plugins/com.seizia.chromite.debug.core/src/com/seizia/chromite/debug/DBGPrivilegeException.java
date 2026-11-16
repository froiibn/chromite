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
package com.seizia.chromite.debug;

import org.jkiss.code.NotNull;

import java.util.List;

/**
 * The user lacks a privilege the debugger needs. Carries the statements a DBA (or the unit's owner) must run.
 *
 * 설계: 권한이 없을 때 서버 오류(ORA-01031 등)만 보이면 무엇을 부탁해야 하는지 알 수 없다. 모델이 빠진 권한을 찾아 실행할 문장을
 * 넘기고, 화면이 사용자 언어로 안내문을 만든다(모델 예외 문구는 영어, AGENTS.md "Hardcode"). 모델은 GRANT를 실행하지 않는다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성(Oracle 디버거) (docs/chromite/procedure-debugger.md)
 */
public class DBGPrivilegeException extends DBGException {

    private static final long serialVersionUID = 1L;

    private final String userName;
    private final List<String> statements;

    /**
     * @param userName   the database user that lacks the privileges
     * @param statements statements that give them (GRANT …), or that only the owner or a DBA can run
     */
    public DBGPrivilegeException(@NotNull String userName, @NotNull List<String> statements) {
        super("User " + userName + " lacks privileges the debugger needs. A DBA must run:\n" + String.join("\n", statements));
        this.userName = userName;
        this.statements = List.copyOf(statements);
    }

    @NotNull
    public String getUserName() {
        return userName;
    }

    @NotNull
    public List<String> getStatements() {
        return statements;
    }
}
