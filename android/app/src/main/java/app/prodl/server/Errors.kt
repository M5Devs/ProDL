package app.prodl.server

/** Error types mapped to HTTP status codes by the StatusPages plugin. */
class ApiNotFound(message: String) : Exception(message)

class ApiBadRequest(message: String) : Exception(message)

class ApiUnprocessable(message: String) : Exception(message)

class ApiTooLarge(message: String) : Exception(message)

class ApiUpstream(message: String) : Exception(message)
