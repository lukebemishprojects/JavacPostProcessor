import com.sun.source.util.Plugin;

module dev.lukebemish.javacpostprocessor {
    requires jdk.compiler;
    requires org.objectweb.asm;

    requires static org.jspecify;
    requires static com.google.auto.service;

    exports dev.lukebemish.javacpostprocessor;

    provides Plugin with dev.lukebemish.javacpostprocessor.PostProcessorPlugin;
    uses dev.lukebemish.javacpostprocessor.PostProcessor;
}
