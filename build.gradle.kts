plugins {
    java
    application
}

group = "com.trading"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

configurations {
    compileOnly {
        extendsFrom(annotationProcessor.get())
    }
}

dependencies {
    // HTTP 클라이언트
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON 처리
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.0")

    // WebSocket
    implementation("org.java-websocket:Java-WebSocket:1.5.4")

    // 로깅
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Apache Commons (유틸리티)
    implementation("org.apache.commons:commons-lang3:3.14.0")
    implementation("org.apache.commons:commons-math3:3.6.1")

    // Lombok (보일러플레이트 코드 감소)
    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")
    testCompileOnly("org.projectlombok:lombok:1.18.30")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.30")

    // 설정 파일 처리
    implementation("com.typesafe:config:1.4.3")

    // 텔레그램 봇 API
    implementation("com.github.pengrad:java-telegram-bot-api:7.0.0")

    // 테스트
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.mockito:mockito-core:5.8.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.trading.bitget.BitgetTradingBot"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}