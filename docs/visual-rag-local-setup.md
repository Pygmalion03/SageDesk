# Visual RAG Local Setup

## What this adds

- A local `Paddle document-analysis bridge` at `http://127.0.0.1:8099/v1/document-analysis`
- Spring local profile config in `bootstrap/src/main/resources/application-local.yaml`
- Multimodal answer path using DashScope Qwen-VL when image evidence is retrieved

## 1. Set env vars

PowerShell:

```powershell
$env:BAILIAN_API_KEY="your-bailian-api-key"
```

Optional bridge auth:

```powershell
$env:PADDLE_BRIDGE_API_KEY="your-local-bridge-key"
```

## 2. Start the local Paddle bridge

```powershell
Set-Location E:\Projects\ragent
.\scripts\start_paddle_bridge.ps1
```

The first run will create `.venv-paddle-bridge` and install:

- `paddlepaddle`
- `paddleocr`
- `PyMuPDF`
- `Pillow`

## 3. Run the Java app with local profile

```powershell
Set-Location E:\Projects\ragent
mvn -pl bootstrap spring-boot:run "-Dspring-boot.run.profiles=local"
```

## 4. Sample file

Current sample file:

```text
E:\Projects\ragent\产品中心_Product_Center：详细目录_+_产品类别_+_型号_+详情描述v43-1-18.pdf
```

## Notes

- The current local bridge runs `PP-StructureV3` in local mode.
- `paddleocr_vl_1_5` is currently aliased to `PP-StructureV3` inside the bridge, because a real remote PaddleOCR-VL service URL has not been configured yet.
- If you later provide the real Paddle remote service URL, the bridge can be extended to proxy to that service without changing the Java ingestion chain.
