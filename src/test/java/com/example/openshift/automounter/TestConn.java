package com.example.openshift.automounter;

import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.openshift.client.OpenShiftConfig;
import io.fabric8.openshift.client.OpenShiftConfigBuilder;

public class TestConn {

	public static void main(String[] args) throws InterruptedException {
		String master = "https://192.168.99.100:8443";

		OpenShiftConfig config = new OpenShiftConfigBuilder()//
				.withMasterUrl(master)//
				.withNamespace("automounter")//
				.withUsername("developer")//
				.withPassword("developer")//
				.build();

		try (OpenShiftClient client = new DefaultOpenShiftClient(config)) {
			// expected exception: User "developer" cannot list namespaces at the cluster scope
			System.out.println("namespaces: " + client.namespaces().list());
		}
	}

}
