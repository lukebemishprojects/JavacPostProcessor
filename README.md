# Javac Post-Processor

A javac plugin (and associated gradle plugin to set it up) for post-processing classes. To make a processor, implement
the `PostProcessor` service.

## Gradle Setup

This adds an extension to all `JavaCompile` tasks allowing configuration of post-processors to run, as below:

```gradle
plugins {
    id 'dev.lukebemish.javac-post-processor' version '<version>'
}

tasks.named('compileJava', JavaCompile) {
    javacPostProcessor {
        plugins.add 'plugin-name-to-enable'
    }
}
```

Both the post-processor javac plugin (`dev.lukebemish:javac-post-processor:version`) and the implementor of
`PostProcessor` should be on the annotation processor path, for instance:

```gradle
dependencies {
    annotationProcessor 'dev.lukebemish:javac-post-processor:<version>'
    annotationProcessor project(':post-processor-impl')
}
```
