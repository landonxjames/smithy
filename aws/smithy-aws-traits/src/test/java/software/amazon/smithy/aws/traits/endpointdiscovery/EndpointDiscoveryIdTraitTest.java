package software.amazon.smithy.aws.traits.endpointdiscovery;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.validation.Severity;
import software.amazon.smithy.model.validation.ValidatedResult;
import software.amazon.smithy.model.validation.ValidationEvent;

public class EndpointDiscoveryIdTraitTest {

    @Test
    public void loadsFromModel() {
        Model result = Model.assembler()
                .discoverModels(getClass().getClassLoader())
                .addImport(getClass().getResource("test-model.json"))
                .assemble()
                .unwrap();
        OperationShape operation = result.getShapeIndex()
                .getShape(ShapeId.from("ns.foo#GetObject")).get()
                .asOperationShape().get();
        MemberShape member = result.getShapeIndex()
                .getShape(operation.getInput().get()).get()
                .asStructureShape().get()
                .getMember("Id").get();

        assertTrue(member.getTrait(EndpointDiscoveryIdTrait.class).isPresent());
    }

    @Test
    public void operationMustHaveDiscoveredEndpointTrait() {
        ValidatedResult<Model> result = Model.assembler()
                .discoverModels(getClass().getClassLoader())
                .addImport(getClass().getResource("no-operation-discovery.json"))
                .assemble();

        List<ValidationEvent> events =  result.getValidationEvents(Severity.ERROR);
        assertThat(events, not(empty()));
        assertThat(events.get(0).getMessage(), containsString("cannot be applied"));
    }
}
