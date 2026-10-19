/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
description = "Simba testing kit: in-memory backend, virtual time and the deterministic simulation"

dependencies {
    api(project(":simba-core"))
}

kotlin {
    compilerOptions {
        optIn.add("me.ahoo.simba.engine.InternalSimbaApi")
    }
}

tasks.withType<Test> {
    // -Psimba.simulation.seeds=10000 runs more seeds; -Psimba.simulation.seed=N replays one.
    listOf("simba.simulation.seeds", "simba.simulation.seed").forEach { key ->
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
}
