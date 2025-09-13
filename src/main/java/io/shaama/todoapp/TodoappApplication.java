package io.shaama.todoapp;

import io.shaama.todoapp.todo.TodoTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class TodoappApplication {

	public static void main(String[] args) {
		SpringApplication.run(TodoappApplication.class, args);
	}

	@Bean(name = "toolCallbackProvider")
	public ToolCallbackProvider toolCallbackProvider(TodoTools todoTools) {
	    return MethodToolCallbackProvider.builder()
	            .toolObjects(todoTools)
	            .build();
	}
}
