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
 *    ./gradlew :smithy-model:jar :smithy-utils:jar :smithy-rules-engine:jar :smithy-jmespath:jar
 *
 * 2. Compile this script:
 *    javac -cp "smithy-rules-engine/build/libs/smithy-rules-engine-1.64.0.jar:smithy-model/build/libs/smithy-model-1.64.0.jar:smithy-utils/build/libs/smithy-utils-1.64.0.jar" convertBdd.java
 *
 * Usage:
 *   java -cp ".:smithy-rules-engine/build/libs/smithy-rules-engine-1.64.0.jar:smithy-model/build/libs/smithy-model-1.64.0.jar:smithy-utils/build/libs/smithy-utils-1.64.0.jar:smithy-jmespath/build/libs/smithy-jmespath-1.64.0.jar" convertBdd <model-path> <service-shape-id>
 *
 * Full working example:
 *   java -cp ".:smithy-rules-engine/build/libs/smithy-rules-engine-1.64.0.jar:smithy-model/build/libs/smithy-model-1.64.0.jar:smithy-utils/build/libs/smithy-utils-1.64.0.jar:smithy-jmespath/build/libs/smithy-jmespath-1.64.0.jar" convertBdd endpointBddSmithyModel.smithy smithy.tests.endpointrules.stringarray#EndpointStringArrayService
 *
 * Output:
 * - Prints the converted endpointBdd trait as JSON
 * - BDD version is automatically upgraded to 1.1 (minimum required)
 * - Base64-encoded nodes represent the optimized decision graph
 */

import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.rulesengine.language.EndpointRuleSet;
import software.amazon.smithy.rulesengine.logic.cfg.Cfg;
import software.amazon.smithy.rulesengine.traits.EndpointBddTrait;
import software.amazon.smithy.rulesengine.traits.EndpointRuleSetTrait;

public class convertBdd {
    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: java convertBdd <model-path> <service-shape-id>");
            System.err.println("Example: java convertBdd model/service.smithy com.example#MyService");
            System.exit(1);
        }

        String modelPath = args[0];
        String serviceShapeId = args[1];

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

        // Apply the BDD trait to the service
        ServiceShape updatedService = service.toBuilder()
            .removeTrait(EndpointRuleSetTrait.ID)
            .addTrait(bddTrait)
            .build();

        // Update the model
        Model updatedModel = model.toBuilder()
            .addShape(updatedService)
            .build();

        // Print the BDD trait
        System.out.println("Conversion successful!");
        System.out.println("\nOriginal endpointRuleSet trait removed.");
        System.out.println("New endpointBdd trait added:");
        System.out.println(Node.prettyPrintJson(bddTrait.toNode()));
    }
}
