# Setup the app

* run `sbt test`
* run `sbt run`

## Interactinng with app

After starting the app with `sbt run`:

Send the POST request with `Content-Type: application/json` with this payload:
  `{"marketId": 123456, "selectionId": 987654, "odds": 2.2, "stake": 253.67, "currency": "USD","date":"2019-05-18T21:32:42.324Z"}`

  to `http://127.0.0.1:8080/api/v1/conversion/trade`.

  Response should be
  ```{
    "currency": "EUR",
    "date": "2019-05-18T21:32:42.324Z",
    "marketId": 123456,
    "odds": 2.2,
    "selectionId": 987654,
    "stake": 227.15134
   }