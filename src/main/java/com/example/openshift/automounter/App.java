package com.example.openshift.automounter;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

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

	public static void main(String[] args) throws InterruptedException {
		String master = "https://192.168.99.100:8443";

		OpenShiftConfig config = new OpenShiftConfigBuilder()//
				.withMasterUrl(master)//
				.withNamespace("automounter")//
				.withUsername("developer")//
				.withPassword("developer")//
				.build();

		try (OpenShiftClient client = new DefaultOpenShiftClient(config)) {
			while (true) {
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

				System.out.println("\nsleeping... ");
				System.out.println();
				Thread.sleep(5000);
			}
		}
	}

}
