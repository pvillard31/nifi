<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at
      http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->

# SampleRecord

This processor takes in a record set and samples records from the set according to the specified sampling strategy. The
available sampling strategies are:

* **Interval Sampling**

  Select every _N_th record based on the value of the Sampling Interval property. For example, if there are 100 records
  in the set and the Sampling Interval is set to 4, there will be 25 records in the output, namely every 4th record.
  This performs uniform sampling of the record set so is best suited for record sets that are uniformly distributed. For
  example a record set representing user information that is uniformly distributed will result in the output records
  also being uniformly distributed. The outgoing record count is deterministic and is exactly the total number of
  records divided by the Sampling Interval value.

* **Probabilistic Sampling**

  Select each record with probability _P_, an integer percentage specified by the Sampling Probability value. For
  example, an incoming record set of 100 records with a Sampling Probability value of 20 should have roughly 20 records
  in the output. Use this when you want to output record sets of roughly the same size (but not exactly) and when you
  want each record to have the same "chance" to be selected for the output set. As another example, if you send the same
  flow file into the processor twice, a sampling strategy of Interval Sampling will always produce the same output,
  where Probabilistic Sampling may output different records (and a different total number of records).

* **Reservoir Sampling**

  Select _K_ records from a record set having _N_ total values, where _K_ is the value of the Reservoir Size property
  and each record has an equal probability of being selected (exactly K / N). For example, an incoming record set of 100
  records with a Reservoir Size value of 20 should have exactly 20 records in the output, randomly chosen from the input
  record set. Use this when you want to control the exact number of output records and have each input record have the
  same probability of being selected. As another example, if you send the same flow file into the processor twice, a
  sampling strategy of Interval Sampling will always produce the same output (same records and number of records), where
  Probabilistic Sampling may output different records (and a different total number of records), and Reservoir Sampling
  may output different records but the same total number of records. Note that the reservoir is kept in-memory, so if
  the size of the reservoir is very large, it may cause memory issues.

The "Random Seed" property applies to strategies/algorithms that use a pseudorandom random number generator, such as
Probabilistic Sampling and Reservoir Sampling. The property is optional but if set will guarantee the same records in a
flow file will be selected by the algorithm each time. This is useful for testing flows using non-deterministic
algorithms such as Probabilistic Sampling and Reservoir Sampling.