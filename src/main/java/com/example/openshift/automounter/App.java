package com.example.openshift.automounter;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimVolumeSource;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.openshift.api.model.DeploymentConfig;
import io.fabric8.openshift.api.model.DeploymentConfigBuilder;
import io.fabric8.openshift.api.model.DeploymentConfigList;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.openshift.client.OpenShiftConfig;
import io.fabric8.openshift.client.OpenShiftConfigBuilder;

public class App {

	private static volatile boolean running = true;

	public static void main(String[] args) throws InterruptedException {
		String masterUrl = "https://openshift.default.svc";
		if (System.getenv("AM_MASTER_URL") != null) {
			masterUrl = System.getenv("AM_MASTER_URL");
		}

		final String NAMESPACE = System.getenv("AM_NAMESPACE");
		final String EXPECTED_POD_NAME = System.getenv("HOSTNAME");

		OpenShiftConfig config = new OpenShiftConfigBuilder()//
				.withMasterUrl(masterUrl)//
				.withNamespace(NAMESPACE)//
				.withOauthToken(System.getenv("AM_TOKEN"))//
				.build();

		try (OpenShiftClient client = new DefaultOpenShiftClient(config)) {
			Runtime.getRuntime().addShutdownHook(new Thread() {
				@Override
				public void run() {
					Event event = new EventBuilder()//
							.withNewMetadata().withGenerateName("automount-")//
							.endMetadata()//
							.withType("Normal")//
							.withLastTimestamp(now())//
							.withInvolvedObject(new ObjectReferenceBuilder()//
									.withNamespace(NAMESPACE)//
									.withKind("Pod")//
									.withName(EXPECTED_POD_NAME).build())//
							.withReason("Exiting")//
							.withMessage("Shutting down")//
							.build();
					client.events().create(event);

					running = false;
				}
			});

			Event event = new EventBuilder()//
					.withNewMetadata().withGenerateName("automount-")//
					.endMetadata()//
					.withType("Normal")//
					.withLastTimestamp(now())//
					.withInvolvedObject(new ObjectReferenceBuilder()//
							.withNamespace(NAMESPACE)//
							.withKind("Pod")//
							.withName(EXPECTED_POD_NAME).build())//
					.withReason("Started")//
					.withMessage("Watching for automount requests")//
					.build();
			client.events().create(event);

			while (running) {
				DeploymentConfigList dcs = client.deploymentConfigs().withLabel("automount", "true").list();

				for (DeploymentConfig dc : dcs.getItems()) {
					String name = dc.getMetadata().getName();
					System.out.print("found: dc/" + name);

					List<Volume> volumes = dc.getSpec().getTemplate().getSpec().getVolumes();
					boolean unmounted = volumes.stream().filter(p -> "automount".equals(p.getName()))
							.collect(Collectors.toList()).isEmpty();

					if (unmounted) {
						System.out.print(" - volume unmounted");

						List<PersistentVolumeClaim> pvcs = client.persistentVolumeClaims().withLabel("automount", name)
								.list().getItems();

						boolean undefined = pvcs.isEmpty();

						if (undefined) {
							System.out.print(" - pvc undefined");

							PersistentVolumeClaim pvc = new PersistentVolumeClaimBuilder()//
									.withNewMetadata()//
									.addToAnnotations("example.com/generated-by", "automounter")//
									.addToLabels("automount", name)//
									.withGenerateName("automount-" + name + "-")//
									.and()//
									.withNewSpec()//
									.withAccessModes("ReadWriteMany")//
									.withNewResources()//
									.withRequests(Collections.singletonMap("storage", new Quantity("10m")))//
									.endResources()//
									.endSpec()//
									.build();

							client.persistentVolumeClaims().create(pvc);
						} else {
							System.out.print(" - pvc defined");

							PersistentVolumeClaim pvc = pvcs.get(0);
							if ("Bound".equals(pvc.getStatus().getPhase())) {
								System.out.print(" - pvc bound");

								VolumeMount volumeMount = new VolumeMountBuilder()//
										.withMountPath("/automounter")//
										.withName("automount")//
										.build();

								PersistentVolumeClaimVolumeSource pvcSource = new PersistentVolumeClaimVolumeSourceBuilder()//
										.withClaimName(pvc.getMetadata().getName())//
										.build();

								Volume volume = new VolumeBuilder()//
										.withName("automount")//
										.withPersistentVolumeClaim(pvcSource)//
										.build();

								DeploymentConfig patchedDc = new DeploymentConfigBuilder(dc)//
										.editSpec()//
										.editTemplate()//
										.editSpec()//
										.addToVolumes(volume)//
										.editFirstContainer()//
										.addNewVolumeMountLike(volumeMount)//
										.endVolumeMount()//
										.endContainer()//
										.endSpec()//
										.endTemplate()//
										.endSpec()//
										.build();//

								client.deploymentConfigs().createOrReplace(patchedDc);
							} else {
								System.out.print(" - pvc unbound (" + pvc.getStatus().getPhase() + ")");
							}
						}
					} else {
						System.out.println(" is as requested");
						// nothing to do
					}
				}
				Thread.sleep(5000);
			}
		}
		System.out.println("exiting");
	}

	private static String now() {
		return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date());
	}

}
