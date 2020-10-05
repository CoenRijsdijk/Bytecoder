/*
 * Copyright 2019 Mirko Sertic
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.mirkosertic.bytecoder.classlib.java.lang;

import de.mirkosertic.bytecoder.api.SubstitutesInClass;

@SubstitutesInClass(completeReplace = true)
public class TThreadGroup {

    public static TThreadGroup SYSTEM = new TThreadGroup();

    private final ThreadGroup parent;
    private final String name;

    private TThreadGroup() {
        name = "system";
        parent = null;
    }

    private TThreadGroup(final String aName) {
        name = aName;
        parent = Thread.currentThread().getThreadGroup();
    }

    private TThreadGroup(final ThreadGroup aParent, final String aName) {
        name = aName;
        parent = aParent;
    }

    public String getName() {
        return name;
    }

    public ThreadGroup getParent() {
        return parent;
    }

    public boolean isDestroyed() {
        return false;
    }
}
