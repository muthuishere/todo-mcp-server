package io.shaama.todoapp.todo.model;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TodoToolResponse {

    private Todo todo;
    private String fact;

}
