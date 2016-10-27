package org.apache.nifi.processors.audio;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.nifi.annotation.behavior.EventDriven;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.InputRequirement.Requirement;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.InputStreamCallback;
import org.apache.nifi.processor.io.OutputStreamCallback;

import edu.cmu.sphinx.api.Configuration;
import edu.cmu.sphinx.api.SpeechResult;
import edu.cmu.sphinx.api.StreamSpeechRecognizer;

@EventDriven
@SupportsBatching
@InputRequirement(Requirement.INPUT_REQUIRED)
@Tags({ "audio", "speech", "recognition", "text", "transcribe", "recognizer", "wav", "aiff", "au", "ogg" })
@CapabilityDescription("This processor takes the content of the incoming flow file as an audio file and transcribes it"
        + " into a text that will be the content of the newly generated flow file. This processor supports wav, aiff,"
        + " au and ogg formats, and expects English as speech language.")
public class SpeechRecognizer extends AbstractProcessor {

    static final Relationship REL_TEXT = new Relationship.Builder()
        .name("text")
        .description("A FlowFile is routed to this relationship if the audio has been successfully transcribed.")
        .build();
    static final Relationship REL_AUDIO = new Relationship.Builder()
        .name("audio")
        .description("The origin FlowFile is routed to this relationship.")
        .build();

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        final List<PropertyDescriptor> properties = new ArrayList<>();
        return properties;
    }

    private final AtomicReference<StreamSpeechRecognizer> recognizer = new AtomicReference<>();

    @Override
    public Set<Relationship> getRelationships() {
        final Set<Relationship> relationships = new HashSet<>();
        relationships.add(REL_TEXT);
        relationships.add(REL_AUDIO);
        return relationships;
    }

    @OnScheduled
    public void onScheduled(ProcessContext context) {
        try {
            Configuration configuration = new Configuration();
            configuration.setAcousticModelPath("resource:/edu/cmu/sphinx/models/en-us/en-us");
            configuration.setDictionaryPath("resource:/edu/cmu/sphinx/models/en-us/cmudict-en-us.dict");
            configuration.setLanguageModelPath("resource:/edu/cmu/sphinx/models/en-us/en-us.lm.bin");
            recognizer.set(new StreamSpeechRecognizer(configuration));
        } catch (IOException e) {
            throw new ProcessException("Error while initializing speech recognizer", e);
        }
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
        final FlowFile flowFile = session.get();
        if (flowFile == null) {
            return;
        }

        // create a flow file from the original
        final List<FlowFile> results = new ArrayList<FlowFile>();

        session.read(flowFile, new InputStreamCallback() {

            @Override
            public void process(InputStream in) throws IOException {
                SpeechResult result;
                recognizer.get().startRecognition(in);
                while ((result = recognizer.get().getResult()) != null) {
                    final String text = result.getHypothesis();
                    FlowFile textResult = session.create(flowFile);
                    textResult = session.write(textResult, new OutputStreamCallback() {
                        @Override
                        public void process(OutputStream out) throws IOException {
                            final PrintStream printStream = new PrintStream(out);
                            printStream.print(text);
                        }
                    });
                    results.add(textResult);
                }
                recognizer.get().stopRecognition();
            }

        });

        session.transfer(results, REL_TEXT);
        session.transfer(flowFile, REL_AUDIO);
    }

}
