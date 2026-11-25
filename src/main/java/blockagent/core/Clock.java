package blockagent.core;

/** 可替換的時鐘，讓單元測試不用真的等待。 */
public interface Clock {
	long millis();

	Clock SYSTEM = System::currentTimeMillis;
}
