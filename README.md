# Todo MCP Server

A Model Context Protocol (MCP) server that provides a Todo application using PostgreSQL database. This server supports three different transport protocols: STDIO, SSE (Server-Sent Events), and Streamable HTTP.

## Features

- **Todo Management**: Full CRUD operations for todo items
- **Multiple Transport Protocols**: Supports STDIO, SSE, and Streamable HTTP transports
- **Profile-based Configuration**: Easy switching between different transport modes
- **Persistent Storage**: PostgreSQL database integration
- **Validation**: Input validation for todo items

## Available Tools

- `getAllTodos`: Retrieve all todo items
- `getTodoById`: Get a specific todo by ID
- `createTodo`: Create a new todo item
- `updateTodo`: Update an existing todo item
- `deleteTodo`: Delete a todo item

## Configuration

### Prerequisites

1. PostgreSQL Database Setup:
   ```bash
   # Create the database
   createdb tododb
   
   # For testing
   createdb tododb_test
   ```

2. Build the project:
   ```bash
   ./gradlew build
   ```

3. The server supports three profiles:
    - `stdio` (default): Standard input/output communication
    - `sse`: Server-Sent Events over HTTP
    - `streamable`: Streamable HTTP transport

### MCP Client Configuration

Add one of the following configurations to your MCP client configuration file:

#### Option 1: STDIO Transport (Recommended)

```json
{
  "mcpServers": {
    "todo-mcp-server": {
      "type": "stdio",
      "command": "java",
      "args": [
        "-Dspring.profiles.active=stdio",
        "-jar",
        "/Users/shaama/Documents/openai-mcp-demo/todo-mcp-server/build/libs/todo-mcp-server_stdio-0.0.1-SNAPSHOT.jar"
      ]
    }
  }
}
```

#### Option 2: SSE Transport

```json
{
  "mcpServers": {
    "todo-mcp-server-sse": {
      "type": "sse",
      "url": "http://localhost:8080/sse"
    }
  }
}
```

Start the SSE server:
```bash
java -Dspring.profiles.active=sse -jar build/libs/todo-mcp-server_sse-0.0.1-SNAPSHOT.jar
```

#### Option 3: Streamable HTTP Transport

```json
{
  "mcpServers": {
    "todo-mcp-server-streamable": {
      "type": "http",
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

Start the Streamable server:
```bash
java -Dspring.profiles.active=streamable -jar build/libs/todo-mcp-server_streamable-0.0.1-SNAPSHOT.jar
```

## Build and Development

### Using Taskfile

This project includes a Taskfile for common operations:

```bash
# Build all profiles
task build

# Build specific profile
task build-stdio
task build-sse
task build-streamable

# Run specific profile
task run-stdio
task run-sse
task run-streamable
```

### Manual Build

```bash
# Build with specific profile
./gradlew bootJar -Pprofile=stdio
./gradlew bootJar -Pprofile=sse
./gradlew bootJar -Pprofile=streamable

# Run tests
./gradlew test
```

## Configuration Files

- `application.properties`: Common configuration (default stdio profile)
- `application-stdio.properties`: STDIO-specific settings
- `application-sse.properties`: SSE-specific settings
- `application-streamable.properties`: Streamable HTTP-specific settings
- `application-test.properties`: Test-specific settings

## Database Configuration

The server uses PostgreSQL for data persistence. Default configuration in `application.properties`:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/tododb
spring.datasource.username=postgres
spring.datasource.password=postgres
spring.jpa.hibernate.ddl-auto=update
```

## Transport Protocol Details

### STDIO Transport
- **Best for**: Direct integration with MCP clients
- **Communication**: Standard input/output streams
- **Port**: Not applicable (no HTTP server)
- **Logging**: File-only to avoid interference with STDIO communication

### SSE Transport
- **Best for**: Web-based clients requiring real-time updates
- **Communication**: HTTP POST requests with Server-Sent Events responses
- **Port**: 8080
- **Endpoint**: `/sse`

### Streamable HTTP Transport
- **Best for**: HTTP-based clients with keep-alive connections
- **Communication**: HTTP POST/GET requests with chunked transfer encoding
- **Port**: 8080
- **Endpoint**: `/mcp`
- **Features**: Session management, resumable connections

## API Endpoints

### REST API
- `GET /api/todos`: Get all todos
- `GET /api/todos/{id}`: Get todo by ID
- `POST /api/todos`: Create new todo
- `PUT /api/todos/{id}`: Update todo
- `DELETE /api/todos/{id}`: Delete todo

## Todo Model

```java
public class Todo {
    private Long id;
    private String title;        // Required
    private String description;  // Optional
    private boolean completed;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

## Example Usage

Once configured, you can use the todo tools in your MCP client:

```
Create a new todo with title "Complete documentation" and description "Write comprehensive README"
```

The server will create a new todo item and return the created object.

## Testing

### Integration Tests

The project includes comprehensive integration tests:

```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests "TodoServiceIntegrationTest"
```

Test coverage includes:
- Todo creation with validation
- Retrieving todos (single and all)
- Updating todos
- Deleting todos
- Error cases and validation

## Troubleshooting

### Database Issues
- Verify PostgreSQL is running: `pg_isready`
- Check database existence: `psql -l | grep tododb`
- Ensure correct credentials in `application.properties`

### STDIO Mode
- Check the log file: `~/todo-mcp-server-stdio.log`
- Ensure no other output is written to stdout

### HTTP Modes (SSE/Streamable)
- Verify the server is running: `curl http://localhost:8080/actuator/health`
- Check server logs for any startup errors
- Ensure port 8080 is available

## Development Guidelines

### Adding New Features
1. Create entity classes in `model` package
2. Implement repository interfaces
3. Add service layer with business logic
4. Create REST controller endpoints
5. Add integration tests
6. Update MCP tool definitions

### Code Style
- Use Lombok annotations for boilerplate reduction
- Implement proper validation
- Follow REST best practices
- Maintain test coverage
