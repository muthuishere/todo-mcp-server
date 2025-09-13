package io.shaama.todoapp.todo;

import io.shaama.todoapp.todo.model.Todo;
import io.shaama.todoapp.todo.model.TodoToolResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static io.shaama.todoapp.utils.Sampling.createSamplingRequest;

@Service
@RequiredArgsConstructor
public class TodoTools {

    private final TodoService todoService;

    @Tool(description = "Gets all Todo items")
    public List<Todo> fetchAllTodos(ToolContext toolContext) {
        return todoService.getAllTodos();
    }

    @Tool(description = "Gets a Todo item by ID")
    public Optional<Todo> fetchTodoById(
            @ToolParam(description = "id for the Item")
            Long id,

            ToolContext toolContext
    ) {
        return todoService.getTodoById(id);
    }

    @Tool(description = "Creates a new Todo item")
    public TodoToolResponse makeTodo(
            @ToolParam(description = "Title for the Todo")
            String title,

            @ToolParam(description = "Description for the Todo")
            String description,

            @ToolParam(description = "Is the Todo completed?")
            boolean completed,

            ToolContext toolContext
    ) {
        Todo todo = Todo.builder()
                .title(title)
                .description(description)
                .completed(completed)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        Todo savedTodo = todoService.createTodo(todo);

        String fact = createSamplingRequest(
                toolContext,
                "You are a fun and witty assistant that provides interesting facts about everyday items.",
                "Provide an interesting fact about a todo item with the title: " + title + " and description: " + description
        );

        return TodoToolResponse.builder()
                .todo(savedTodo)
                .fact(fact)
                .build();
    }

    @Tool(description = "Updates an existing Todo item")
    public Optional<Todo> changeTodo(
            @ToolParam(description = "id for the Item")
            Long id,

            @ToolParam(description = "Title for the Todo")
            String title,

            @ToolParam(description = "Description for the Todo")
            String description,

            @ToolParam(description = "Is the Todo completed?")
            boolean completed,

            ToolContext toolContext
    ) {
        return todoService.getTodoById(id).map(todo -> {
            todo.setTitle(title);
            todo.setDescription(description);
            todo.setCompleted(completed);
            todo.setUpdatedAt(LocalDateTime.now());
            return todoService.createTodo(todo);
        });
    }

    @Tool(description = "Deletes a Todo item by ID")
    public boolean removeTodo(
            @ToolParam(description = "id for the Item")
            Long id,

            ToolContext toolContext
    ) {
        return todoService.getTodoById(id).map(todo -> {
            todoService.deleteTodo(id);
            return true;

        }).orElse(false);
    }
}
