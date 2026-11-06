/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
package com.seizia.chromite.ui;

import org.eclipse.jface.action.Action;
import com.seizia.chromite.ui.internal.UIMessages;

/**
 * Context search action
 */
public class ContextSearchAction extends Action {

    private final ISearchContextProvider contextProvider;
    private final ISearchContextProvider.SearchType searchType;

    public ContextSearchAction(ISearchContextProvider contextProvider, ISearchContextProvider.SearchType searchType)
    {
        super(UIMessages.ui_actions_context_search_name, SwtIcons.getImageDescriptor(UIIcon.FIND));
        this.contextProvider = contextProvider;
        this.searchType = searchType;
    }

    @Override
    public void run()
    {
        if (contextProvider.isSearchEnabled()) {
            contextProvider.performSearch(searchType);
        }
    }
}
