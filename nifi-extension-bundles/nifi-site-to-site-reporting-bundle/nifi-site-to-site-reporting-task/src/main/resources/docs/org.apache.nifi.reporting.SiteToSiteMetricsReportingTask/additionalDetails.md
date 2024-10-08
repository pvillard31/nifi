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

# SiteToSiteMetricsReportingTask

The Site-to-Site Metrics Reporting Task allows the user to publish NiFi's metrics (as in the Ambari reporting task) to
the same NiFi instance or another NiFi instance. This provides a great deal of power because it allows the user to make
use of all the different Processors that are available in NiFi in order to process or distribute that data.

## Ambari format

There are two available output formats. The first one is the Ambari format as defined in the Ambari Metrics Collector
API which is a JSON with dynamic keys. If using this format you might be interested in the below Jolt specification to
transform the data.

```json
[
  {
    "operation": "shift",
    "spec": {
      "metrics": {
        "*": {
          "metrics": {
            "*": {
              "$": "metrics.[#4].metrics.time",
              "@": "metrics.[#4].metrics.value"
            }
          },
          "*": "metrics.[&1].&"
        }
      }
    }
  }
]
```

This would transform the below sample:

```json
{
  "metrics": [
    {
      "metricname": "jvm.gc.time.G1OldGeneration",
      "appid": "nifi",
      "instanceid": "8927f4c0-0160-1000-597a-ea764ccd81a7",
      "hostname": "localhost",
      "timestamp": "1520456854361",
      "starttime": "1520456854361",
      "metrics": {
        "1520456854361": "0"
      }
    },
    {
      "metricname": "jvm.thread_states.terminated",
      "appid": "nifi",
      "instanceid": "8927f4c0-0160-1000-597a-ea764ccd81a7",
      "hostname": "localhost",
      "timestamp": "1520456854361",
      "starttime": "1520456854361",
      "metrics": {
        "1520456854361": "0"
      }
    }
  ]
}
```

into:

```json
{
  "metrics": [
    {
      "metricname": "jvm.gc.time.G1OldGeneration",
      "appid": "nifi",
      "instanceid": "8927f4c0-0160-1000-597a-ea764ccd81a7",
      "hostname": "localhost",
      "timestamp": "1520456854361",
      "starttime": "1520456854361",
      "metrics": {
        "time": "1520456854361",
        "value": "0"
      }
    },
    {
      "metricname": "jvm.thread_states.terminated",
      "appid": "nifi",
      "instanceid": "8927f4c0-0160-1000-597a-ea764ccd81a7",
      "hostname": "localhost",
      "timestamp": "1520456854361",
      "starttime": "1520456854361",
      "metrics": {
        "time": "1520456854361",
        "value": "0"
      }
    }
  ]
}
```

## Record format

The second format is leveraging the record framework of NiFi so that the user can define a Record Writer and directly
specify the output format and data with the assumption that the input schema is the following:

```json
{
  "type": "record",
  "name": "metrics",
  "namespace": "metrics",
  "fields": [
    {
      "name": "appid",
      "type": "string"
    },
    {
      "name": "instanceid",
      "type": "string"
    },
    {
      "name": "hostname",
      "type": "string"
    },
    {
      "name": "timestamp",
      "type": "long"
    },
    {
      "name": "loadAverage1min",
      "type": "double"
    },
    {
      "name": "availableCores",
      "type": "int"
    },
    {
      "name": "FlowFilesReceivedLast5Minutes",
      "type": "int"
    },
    {
      "name": "BytesReceivedLast5Minutes",
      "type": "long"
    },
    {
      "name": "FlowFilesSentLast5Minutes",
      "type": "int"
    },
    {
      "name": "BytesSentLast5Minutes",
      "type": "long"
    },
    {
      "name": "FlowFilesQueued",
      "type": "int"
    },
    {
      "name": "BytesQueued",
      "type": "long"
    },
    {
      "name": "BytesReadLast5Minutes",
      "type": "long"
    },
    {
      "name": "BytesWrittenLast5Minutes",
      "type": "long"
    },
    {
      "name": "ActiveThreads",
      "type": "int"
    },
    {
      "name": "TotalTaskDurationSeconds",
      "type": "long"
    },
    {
      "name": "TotalTaskDurationNanoSeconds",
      "type": "long"
    },
    {
      "name": "jvmuptime",
      "type": "long"
    },
    {
      "name": "jvmheap_used",
      "type": "double"
    },
    {
      "name": "jvmheap_usage",
      "type": "double"
    },
    {
      "name": "jvmnon_heap_usage",
      "type": "double"
    },
    {
      "name": "jvmthread_statesrunnable",
      "type": [
        "int",
        "null"
      ]
    },
    {
      "name": "jvmthread_statesblocked",
      "type": [
        "int",
        "null"
      ]
    },
    {
      "name": "jvmthread_statestimed_waiting",
      "type": [
        "int",
        "null"
      ]
    },
    {
      "name": "jvmthread_statesterminated",
      "type": [
        "int",
        "null"
      ]
    },
    {
      "name": "jvmthread_count",
      "type": "int"
    },
    {
      "name": "jvmdaemon_thread_count",
      "type": "int"
    },
    {
      "name": "jvmfile_descriptor_usage",
      "type": "double"
    },
    {
      "name": "jvmgcruns",
      "type": [
        "long",
        "null"
      ]
    },
    {
      "name": "jvmgctime",
      "type": [
        "long",
        "null"
      ]
    }
  ]
}
```