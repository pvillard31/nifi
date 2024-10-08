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

# ADLSCredentialsControllerService

### Security considerations of using Expression Language for sensitive properties

Allowing Expression Language for a property has the advantage of configuring the property dynamically via FlowFile
attributes or Variable Registry entries. In case of sensitive properties, it also has a drawback of exposing sensitive
information like passwords, security keys or tokens. When the value of a sensitive property comes from a FlowFile
attribute, it travels by the FlowFile in clear text form and is also saved in the provenance repository. Variable
Registry does not support the encryption of sensitive information either. Due to these, the sensitive credential data
can be exposed to unauthorized parties.

Best practices for using Expression Language for sensitive properties:

* use it only if necessary
* control access to the flow and to provenance repository
* encrypt disks storing FlowFiles and provenance data
* if the sensitive data is a temporary token (like the SAS token), use a shorter lifetime and refresh the token
  periodically