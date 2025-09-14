

# Tasks

## Make it deployable to AWS Lambda

The  taskfile should have an entry to setup infrastructure and then a deploy command and also a destroy command  to make our application in aws and it should should show the deployed url for aws lambda


## Make it doable deploy from cd pipeline as well

No one works from local so it should work from cd with a release


deployed again host
2025-09-14 14:15:31.794 [info] Connection state: Running
2025-09-14 14:15:33.010 [info] Connection state: Error 502 status sending message to https://dhgo2vgcaqoi64d3iyiao77yci0ybuyg.lambda-url.us-east-1.on.aws/mcp: Internal Server Error
2025-09-14 14:15:33.011 [error] Server exited before responding to `initialize` request.
::: and current streams are :: {
"logStreams": [
{
"logStreamName": "2025/09/14/[$LATEST]440707de54a34dffa00df1b4c952704a",
"creationTime": 1757839532979,
"firstEventTimestamp": 1757839532932,
"lastEventTimestamp": 1757839532933,
"lastIngestionTime": 1757839532988,
"uploadSequenceToken": "49039859644277962812598865089453171910316119489110239085",
"arn": "arn:aws:logs:us-east-1:663532473958:log-group:/aws/lambda/todo-app-function:log-stream:2025/09/14/[$LATEST]440707de54a34dffa00df1b4c952704a",
"storedBytes": 0
},
{
"logStreamName": "2025/09/14/[$LATEST]abf6a47907dc42f9b21695517762937a",
"creationTime": 1757839185559,
"firstEventTimestamp": 1757839185506,
"lastEventTimestamp": 1757839185507,
"lastIngestionTime": 1757839185567,
"uploadSequenceToken": "49039859644277501010879341498195693053519927447018432272",
"arn": "arn:aws:logs:us-east-1:663532473958:log-group:/aws/lambda/todo-app-function:log-stream:2025/09/14/[$LATEST]abf6a47907dc42f9b21695517762937a",
"storedBytes": 0
},
{
"logStreamName": "2025/09/14/[$LATEST]ffd4d222ac91498c97911b1fc09229f7",
"creationTime": 1757838584352,
"firstEventTimestamp": 1757838584295,
"lastEventTimestamp": 1757838584331,
"lastIngestionTime": 1757838584387,
"uploadSequenceToken": "49039859644276701905592835522471221303255394536469718845",
"arn": "arn:aws:logs:us-east-1:663532473958:log-group:/aws/lambda/todo-app-function:log-stream:2025/09/14/[$LATEST]ffd4d222ac91498c97911b1fc09229f7", :::,