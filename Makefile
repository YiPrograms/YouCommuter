.PHONY: test test-backend test-android build-backend build-android fmt check-fmt dev down collect integration integration-compose

test: test-backend test-android

test-backend:
	go test -race ./...
	go vet ./...

test-android:
	cd android && ./gradlew :core:domain:test :core:data:test :core:database:testDebugUnitTest :feature:routes:testDebugUnitTest :feature:stations:testDebugUnitTest :feature:commute:testDebugUnitTest :core:map:testDebugUnitTest :app:testDebugUnitTest :core:platform:lintDebug :app:lintDebug

build-backend:
	mkdir -p bin
	go build -trimpath -buildvcs=false -o bin/server ./backend/cmd/server
	go build -trimpath -buildvcs=false -o bin/initdb ./backend/cmd/initdb
	go build -trimpath -buildvcs=false -o bin/collect ./backend/cmd/collect
	go build -trimpath -buildvcs=false -o bin/healthcheck ./backend/cmd/healthcheck

build-android:
	cd android && ./gradlew :app:assembleDebug

fmt:
	gofmt -w backend db

check-fmt:
	@test -z "$$(gofmt -l backend db)" || (gofmt -l backend db; exit 1)

dev:
	docker compose up --build -d

down:
	docker compose down

collect:
	docker compose up --build -d

integration:
	./scripts/test-database.sh

integration-compose:
	./scripts/test-compose.sh
