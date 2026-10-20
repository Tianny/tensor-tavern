package com.hify.workflow.exception;

/**
 * 工作流执行失败（引擎内部控制流）。message 为可直接写入运行记录、
 * 展示给用户的失败原因，不携带堆栈语义。
 */
public class WorkflowExecutionException extends RuntimeException {

    public WorkflowExecutionException(String userMessage) {
        super(userMessage);
    }
}
