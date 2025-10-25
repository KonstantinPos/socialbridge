plugins {
	kotlin("jvm") version "1.9.25"
	kotlin("plugin.spring") version "1.9.25"
	id("org.springframework.boot") version "3.5.6"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.posysaev.socialbridge"
version = "0.0.1-SNAPSHOT"
description = "Demo project for Spring Boot"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
	maven("https://mvn.mchv.eu/repository/mchv/")
}

dependencies {
	testImplementation(kotlin("test"))

	implementation(platform("it.tdlight:tdlight-java-bom:3.4.0+td.1.8.26"))
	implementation("it.tdlight:tdlight-java")
	implementation("it.tdlight:tdlight-natives") {
		artifact {
			classifier = "windows_amd64"
		}
	}
	implementation("it.tdlight:tdlight-natives") {
		artifact {
			classifier = "linux_amd64_gnu_ssl3"
		}
	}


	// Existing dependencies
	implementation("com.squareup.okhttp3:okhttp:4.12.0")
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.jsoup:jsoup:1.16.1")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}