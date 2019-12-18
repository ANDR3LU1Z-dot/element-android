/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.riotx.core.epoxy

import com.airbnb.epoxy.EpoxyModelClass
import im.vector.riotx.R

/**
 * Item of size (0, 0).
 * It can be useful to avoid automatic scroll of RecyclerView with Epoxy controller, when the first valuable item changes.
 */
@EpoxyModelClass(layout = R.layout.item_zero)
abstract class ZeroItem : VectorEpoxyModel<ZeroItem.Holder>() {

    class Holder : VectorEpoxyHolder()
}
