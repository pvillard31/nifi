package org.apache.nifi.processors.audio;

import java.io.File;
import java.io.IOException;

import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.Before;
import org.junit.Test;

public class SpeechRecognizerTest {

    private TestRunner testRunner;

    @Before
    public void init() {
        testRunner = TestRunners.newTestRunner(SpeechRecognizer.class);
    }

    @Test
    public void testCorrectFile() throws IOException {
        testRunner.enqueue(new File("src/test/resources/audio/10001-90210-01803.wav").toPath());
        testRunner.run();

        testRunner.assertTransferCount(SpeechRecognizer.REL_AUDIO, 1);
        testRunner.assertTransferCount(SpeechRecognizer.REL_TEXT, 3);

        MockFlowFile flowFile = testRunner.getFlowFilesForRelationship(SpeechRecognizer.REL_TEXT).get(0);
        flowFile.assertContentEquals("what zero zero zero one");
        flowFile = testRunner.getFlowFilesForRelationship(SpeechRecognizer.REL_TEXT).get(1);
        flowFile.assertContentEquals("nine oh two one oh");
        flowFile = testRunner.getFlowFilesForRelationship(SpeechRecognizer.REL_TEXT).get(2);
        flowFile.assertContentEquals("zero one eight zero three");
    }

    @Test
    public void testIncorrectFile() throws IOException {
        testRunner.enqueue(new File("src/test/resources/textFile.txt").toPath());
        testRunner.run();
        testRunner.assertTransferCount(SpeechRecognizer.REL_AUDIO, 1);
        testRunner.assertTransferCount(SpeechRecognizer.REL_TEXT, 1);
        MockFlowFile flowFile = testRunner.getFlowFilesForRelationship(SpeechRecognizer.REL_TEXT).get(0);
        flowFile.assertContentEquals("");
    }

}
