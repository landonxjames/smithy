/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

/*
 * Converts a Smithy model with endpointRuleSet trait to endpointBdd trait.
 *
 * The conversion process:
 * 1. Parses the endpointRuleSet into an EndpointRuleSet object
 * 2. Converts to CFG (Control Flow Graph) - intermediate representation
 * 3. Compiles to BDD (Binary Decision Diagram) - optimized decision graph
 *
 * Prerequisites:
 * 1. Build required Smithy modules:
 *    ./gradlew :smithy-model:jar :smithy-utils:jar :smithy-rules-engine:jar :smithy-jmespath:jar :smithy-aws-traits:jar :smithy-waiters:jar :smithy-aws-endpoints:jar
 *
 * 2. Compile this script:
 *    javac -cp "smithy-rules-engine/build/libs/smithy-rules-engine-1.64.0.jar:smithy-model/build/libs/smithy-model-1.64.0.jar:smithy-utils/build/libs/smithy-utils-1.64.0.jar" convertBdd.java
 *
 * Usage (basic models):
 *   java -cp ".:smithy-rules-engine/build/libs/smithy-rules-engine-1.64.0.jar:smithy-model/build/libs/smithy-model-1.64.0.jar:smithy-utils/build/libs/smithy-utils-1.64.0.jar:smithy-jmespath/build/libs/smithy-jmespath-1.64.0.jar" convertBdd <model-path> <service-shape-id> <output-directory>
 *
 * Usage (AWS models - includes AWS-specific traits and functions):
 *   java -cp ".:smithy-rules-engine/build/libs/smithy-rules-engine-1.64.0.jar:smithy-model/build/libs/smithy-model-1.64.0.jar:smithy-utils/build/libs/smithy-utils-1.64.0.jar:smithy-jmespath/build/libs/smithy-jmespath-1.64.0.jar:smithy-aws-traits/build/libs/smithy-aws-traits-1.64.0.jar:smithy-waiters/build/libs/smithy-waiters-1.64.0.jar:smithy-aws-endpoints/build/libs/smithy-aws-endpoints-1.64.0.jar" convertBdd <model-path> <service-shape-id> <output-directory>
 *
 * Full working example (basic):
 *   java -cp ".:smithy-rules-engine/build/libs/smithy-rules-engine-1.64.0.jar:smithy-model/build/libs/smithy-model-1.64.0.jar:smithy-utils/build/libs/smithy-utils-1.64.0.jar:smithy-jmespath/build/libs/smithy-jmespath-1.64.0.jar" convertBdd endpointBddSmithyModel.smithy smithy.tests.endpointrules.stringarray#EndpointStringArrayService convertBdd-output/
 *
 * Full working example (AWS S3):
 *   java -cp ".:smithy-rules-engine/build/libs/smithy-rules-engine-1.64.0.jar:smithy-model/build/libs/smithy-model-1.64.0.jar:smithy-utils/build/libs/smithy-utils-1.64.0.jar:smithy-jmespath/build/libs/smithy-jmespath-1.64.0.jar:smithy-aws-traits/build/libs/smithy-aws-traits-1.64.0.jar:smithy-waiters/build/libs/smithy-waiters-1.64.0.jar:smithy-aws-endpoints/build/libs/smithy-aws-endpoints-1.64.0.jar" convertBdd /path/to/s3.json com.amazonaws.s3#AmazonS3 s3-bdd-output/
 *
 * Output:
 * - Saves JSON AST model to <output-directory>/model.json
 * - Saves Smithy IDL files to <output-directory>/*.smithy
 * - Prints the converted endpointBdd trait as JSON to console
 * - BDD version is automatically upgraded to 1.1 (minimum required)
 * - Base64-encoded nodes represent the optimized decision graph
 */

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.shapes.ModelSerializer;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.SmithyIdlModelSerializer;
import software.amazon.smithy.model.loader.ModelAssembler;
import software.amazon.smithy.rulesengine.language.EndpointRuleSet;
import software.amazon.smithy.rulesengine.logic.cfg.Cfg;
import software.amazon.smithy.rulesengine.logic.bdd.SiftingOptimization;
import software.amazon.smithy.rulesengine.logic.bdd.NodeReversal;
import software.amazon.smithy.rulesengine.traits.EndpointBddTrait;
import software.amazon.smithy.rulesengine.traits.EndpointRuleSetTrait;

public class convertBdd {
    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println("Usage: java convertBdd <model-path> <service-shape-id> <output-directory>");
            System.err.println("Example: java convertBdd model/service.smithy com.example#MyService output/");
            System.exit(1);
        }

        String modelPath = args[0];
        String serviceShapeId = args[1];
        String outputDir = args[2];

        // Load your model with trait discovery
        Model model = Model.assembler()
            .discoverModels()
            .addImport(modelPath)
            .assemble()
            .unwrap();

        // Get the service shape
        ServiceShape service = model.expectShape(
            ShapeId.from(serviceShapeId), 
            ServiceShape.class
        );

        // Get the endpointRuleSet trait
        EndpointRuleSetTrait ruleSetTrait = service.expectTrait(EndpointRuleSetTrait.class);
        EndpointRuleSet ruleSet = ruleSetTrait.getEndpointRuleSet();

        // Convert: RuleSet -> CFG -> BDD
        Cfg cfg = Cfg.from(ruleSet);
        EndpointBddTrait bddTrait = EndpointBddTrait.from(cfg);
        bddTrait = SiftingOptimization.builder().cfg(cfg).build().apply(bddTrait);
        bddTrait = new NodeReversal().apply(bddTrait);

        // Apply the BDD trait to the service
        ServiceShape updatedService = service.toBuilder()
            .removeTrait(EndpointRuleSetTrait.ID)
            .addTrait(bddTrait)
            .build();

        // Update the model
        Model updatedModel = model.toBuilder()
            .addShape(updatedService)
            .build();

        // Save model to output directory
        try {
            Path outputPath = Paths.get(outputDir);
            Files.createDirectories(outputPath);
            
            // Save JSON AST version
            Path jsonFile = outputPath.resolve("model.json");
            ModelSerializer jsonSerializer = ModelSerializer.builder().build();
            String modelJson = Node.prettyPrintJson(jsonSerializer.serialize(updatedModel));
            Files.writeString(jsonFile, modelJson);
            
            // Save Smithy IDL version
            SmithyIdlModelSerializer idlSerializer = SmithyIdlModelSerializer.builder().build();
            Map<Path, String> smithyFiles = idlSerializer.serialize(updatedModel);
            for (Map.Entry<Path, String> entry : smithyFiles.entrySet()) {
                Path smithyFile = outputPath.resolve(entry.getKey());
                Files.createDirectories(smithyFile.getParent());
                Files.writeString(smithyFile, entry.getValue());
            }
            
            System.out.println("Conversion successful!");
            System.out.println("JSON model saved to: " + jsonFile.toAbsolutePath());
            System.out.println("Smithy IDL files saved to: " + outputPath.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Error writing model to output directory: " + e.getMessage());
            System.exit(1);
        }

        // Print the BDD trait
        System.out.println("\nOriginal endpointRuleSet trait removed.");
        System.out.println("New endpointBdd trait added:");
        System.out.println(Node.prettyPrintJson(bddTrait.toNode()));
    }
}
