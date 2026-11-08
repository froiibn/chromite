# Chromite - Database Workbench
# Copyright (C) 2026 Chromite contributors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# dmgbuild settings of the Chromite disk image (tools/release/release-macos.sh "package"). dmgbuild runs this
# file with the dict `defines` from its -D options:
#   app         the app bundle to put on the image (Chromite.app)
#   background  the window background (a TIFF holding background.png and background@2x.png)
#
# Design (docs/chromite/distribution.md "macOS disk image window", app change #26): when the image opens, the
# app sits at the top centre and the Applications folder below it, so the user drags from top to bottom;
# icons 144 pt; a fixed window without tool bar, path bar, side bar or status bar; the down arrow of the
# background (DmgBackground.java, same sizes) between them. dmgbuild writes the window's .DS_Store itself
# (ds_store, mac_alias), without Finder scripting, so it runs the same on a CI runner without a GUI session.
#
# Change history:
# - 2026-11-09 Created (app change #26).

import os.path

app = defines["app"]  # noqa: F821 (given by dmgbuild)
app_name = os.path.basename(app)

format = "UDZO"
filesystem = "HFS+"
files = [app]
symlinks = {"Applications": "/Applications"}
hide_extension = [app_name]

background = defines["background"]  # noqa: F821
# Window bounds in points: the size of background.png (480 x 580)
window_rect = ((200, 120), (480, 580))
default_view = "icon-view"
show_status_bar = False
show_tab_view = False
show_toolbar = False
show_pathbar = False
show_sidebar = False
show_icon_preview = False
include_icon_view_settings = True
include_list_view_settings = False

arrange_by = None
icon_size = 144
text_size = 13
label_pos = "bottom"
# Centres of the icons (DmgBackground.APP_Y, APPLICATIONS_Y)
icon_locations = {
    app_name: (240, 150),
    "Applications": (240, 430),
}
