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

package me.ahoo.simba.example;

import me.ahoo.simba.Simba;
import me.ahoo.simba.testing.InMemoryLeaseBackend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExampleAppTest {
    @Test
    void runStartsTheElectionAndContextCloseStopsIt() throws Exception {
        try (Simba simba = Simba.builder().backend(new InMemoryLeaseBackend()).build();
             AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(Simba.class, () -> simba, definition -> definition.setDestroyMethodName(""));
            context.registerBean(CommandLineRunner.class, () -> new ExampleApp(simba));
            context.refresh();

            context.getBean(CommandLineRunner.class).run();
            assertEquals(1, simba.snapshot().getElections().size());

            context.close();
            assertTrue(simba.snapshot().getElections().isEmpty());
        }
    }
}
