package io.github.sunjirapath.bootcontextdiff;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.support.GenericApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class ContextContractScaleTest {

	@TempDir
	Path dir;

	@Test
	@Timeout(60)
	void sixThousandLazyDefinitionsWriteVerifyAndRemainUninstantiated() throws Exception {
		try (var context = new GenericApplicationContext()) {
			for (int i = 0; i < 6_000; i++) {
				RootBeanDefinition definition = new RootBeanDefinition(String.class);
				definition.setLazyInit(true);
				context.registerBeanDefinition("scale-bean-" + i, definition);
			}
			Path baseline = dir.resolve("scale.json");
			ContextContract.write(context, baseline);
			ContextContract.verify(context, baseline);
			assertThat(ContextSnapshot.of(context).beans()).hasSize(6_000);
			assertThat(Files.size(baseline)).isLessThan(JsonCodec.MAX_BASELINE_BYTES);
			assertThat(context.getBeanFactory().getSingletonNames()).isEmpty();
		}
	}

}
