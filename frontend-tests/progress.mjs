export default class Progress {
  onStepBegin(test, result, step) {
    if (step.category === 'test.step') process.stdout.write('  → ' + step.title + '\n');
  }
}
