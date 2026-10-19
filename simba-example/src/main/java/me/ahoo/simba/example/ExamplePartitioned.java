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

import me.ahoo.simba.Term;
import me.ahoo.simba.spring.boot.starter.scheduling.SimbaPartitioned;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Example partitioned work: the four partitions of {@code example-billing} are spread over the running instances,
 * each partition on exactly one of them at a time.
 *
 * @author ahoo wang
 */
@Service
@Slf4j
public class ExamplePartitioned {

    @SimbaPartitioned(group = "example-billing", partitions = 4, fixedDelay = "10s")
    public void bill(Term term, int partition) {
        log.info("bill partition:[{}] token:[{}]", partition, term.getToken());
    }
}
