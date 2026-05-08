load("@rules_java//java:defs.bzl", "java_library")
load("//tools/bzl:junit.bzl", "junit_tests")
load("//tools/bzl:plugin.bzl", "PLUGIN_DEPS", "PLUGIN_TEST_DEPS", "gerrit_plugin")

gerrit_plugin(
    name = "reviewtarget",
    srcs = glob(["server/**/*.java"]),
    manifest_entries = [
        "Gerrit-PluginName: reviewtarget",
        "Gerrit-Module: com.googlesource.gerrit.plugins.reviewtarget.PluginModule",
        "Gerrit-HttpModule: com.googlesource.gerrit.plugins.reviewtarget.HttpModule",
        "Gerrit-BatchModule: com.googlesource.gerrit.plugins.reviewtarget.BatchModule",
        "Implementation-Title: Post-Commit review by following a target branch",
    ],
    resource_jars = ["//plugins/reviewtarget/ui:reviewtarget"],
    resource_strip_prefix = "plugins/reviewtarget/resources",
    resources = glob(["resources/**/*"]),
)

junit_tests(
    name = "reviewtarget_tests",
    srcs = glob(["test-server/**/*.java"]),
    tags = ["reviewtarget"],
    deps = [
        ":reviewtarget__plugin_test_deps",
    ],
)

java_library(
    name = "reviewtarget__plugin_test_deps",
    testonly = 1,
    visibility = ["//visibility:public"],
    exports = PLUGIN_DEPS + PLUGIN_TEST_DEPS + [
        ":reviewtarget__plugin",
        "@commons-io//jar",
        "@mockito//jar",
    ],
)
