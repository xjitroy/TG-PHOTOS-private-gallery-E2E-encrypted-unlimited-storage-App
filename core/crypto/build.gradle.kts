plugins { `java-library` }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.83")
    testImplementation(libs.junit)
}
