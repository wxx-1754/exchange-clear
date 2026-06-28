package com.wuxx.exchangeclear.generator;

public interface FileGenerator {

    boolean support(String fileType);

    FileGenerateResult generate(FileGenerateContext context);
}
